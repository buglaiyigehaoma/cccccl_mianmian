package com.cccccl.mianmian.service.impl;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cccccl.mianmian.common.ErrorCode;
import com.cccccl.mianmian.constant.CommonConstant;
import com.cccccl.mianmian.exception.BusinessException;
import com.cccccl.mianmian.exception.ThrowUtils;
import com.cccccl.mianmian.manager.AIManager;
import com.cccccl.mianmian.mapper.MockInterviewMapper;
import com.cccccl.mianmian.model.dto.mockinterview.MockInterviewAddRequest;
import com.cccccl.mianmian.model.dto.mockinterview.MockInterviewChatMessage;
import com.cccccl.mianmian.model.dto.mockinterview.MockInterviewEventRequest;
import com.cccccl.mianmian.model.dto.mockinterview.MockInterviewQueryRequest;
import com.cccccl.mianmian.model.entity.MockInterview;
import com.cccccl.mianmian.model.entity.User;
import com.cccccl.mianmian.model.enums.MockInterviewEventEnum;
import com.cccccl.mianmian.model.enums.MockInterviewStatusEnum;
import com.cccccl.mianmian.service.MockInterviewService;
import com.cccccl.mianmian.utils.SqlUtils;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import org.apache.poi.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
* @author cccccl
* @description 针对表【mock_interview(模拟面试)】的数据库操作Service实现
* @createDate 2025-07-31 22:43:46
*/
@Service
public class MockInterviewServiceImpl extends ServiceImpl<MockInterviewMapper, MockInterview>
    implements MockInterviewService {

    @Autowired
    private AIManager aiManager;

    /**
     * 创建模拟面试
     * @param mockInterviewAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public Long createMockInterview(MockInterviewAddRequest mockInterviewAddRequest, User loginUser) {
        // 1. 参数校验
        if(mockInterviewAddRequest == null || loginUser == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String workExperience = mockInterviewAddRequest.getWorkExperience();
        String jobPosition = mockInterviewAddRequest.getJobPosition();
        String difficulty = mockInterviewAddRequest.getDifficulty();
        ThrowUtils.throwIf(StrUtil.hasBlank(workExperience, jobPosition, difficulty), ErrorCode.PARAMS_ERROR, "参数错误");
        // 2. 封装插入到数据库中的对象
        MockInterview mockInterview = new MockInterview();
        mockInterview.setWorkExperience(workExperience);
        mockInterview.setJobPosition(jobPosition);
        mockInterview.setDifficulty(difficulty);
        mockInterview.setUserId(loginUser.getId());
        mockInterview.setStatus(MockInterviewStatusEnum.TO_START.getValue());

        // 3. 插入到数据库
        boolean result = this.save(mockInterview);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建失败");
        // 4. 返回 id
        return mockInterview.getId();
    }

    /**
     * 获取查询条件
     *
     * @param mockInterviewQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<MockInterview> getQueryWrapper(MockInterviewQueryRequest mockInterviewQueryRequest) {
        QueryWrapper<MockInterview> queryWrapper = new QueryWrapper<>();
        if (mockInterviewQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = mockInterviewQueryRequest.getId();
        String workExperience = mockInterviewQueryRequest.getWorkExperience();
        String jobPosition = mockInterviewQueryRequest.getJobPosition();
        String difficulty = mockInterviewQueryRequest.getDifficulty();
        Integer status = mockInterviewQueryRequest.getStatus();
        Long userId = mockInterviewQueryRequest.getUserId();
        String sortField = mockInterviewQueryRequest.getSortField();
        String sortOrder = mockInterviewQueryRequest.getSortOrder();
        // 补充需要的查询条件
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.like(StringUtils.isNotBlank(workExperience), "workExperience", workExperience);
        queryWrapper.like(StringUtils.isNotBlank(jobPosition), "jobPosition", jobPosition);
        queryWrapper.like(StringUtils.isNotBlank(difficulty), "difficulty", difficulty);
        queryWrapper.eq(ObjectUtils.isNotEmpty(status), "status", status);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        // 排序规则
        queryWrapper.orderBy(SqlUtils.validSortField(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 处理模拟面试事件
     * @param mockInterviewEventRequest
     * @param loginUser
     * @return
     */
    @Override
    public String handleMockInterviewEvent(MockInterviewEventRequest mockInterviewEventRequest, User loginUser) {
        // 区分事件
        Long id = mockInterviewEventRequest.getId();
        if(id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数错误");
        }
        MockInterview mockInterview = this.getById(id);
        ThrowUtils.throwIf(mockInterview == null, ErrorCode.PARAMS_ERROR, "模拟面试未创建");
        if (!mockInterview.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        String event = mockInterviewEventRequest.getEvent();
        MockInterviewEventEnum eventEnum = MockInterviewEventEnum.getEnumByValue(event);

        switch (eventEnum) {
            case START:
            // 处理开始事件
            // 用户进入 模拟 面试，发送“开始”事件，修改模拟面试状态为“已开始”，AI 给出相应的答复
                return handleChatStartEvent(mockInterview);
            case CHAT:
                // 处理对话事件
                // 用户退出模拟面试，发送“退出”事件，AI 给出面试的复盘总结，修改状态为“已结束”
                return handleChatMessageEvent(mockInterviewEventRequest, mockInterview);
            case END:
                // 处理结束事件
                // 用户退出模拟面试，发送“退出”事件，AI 给出面试的复盘总结，修改状态为“已结束”
                return handleChatEndEvent(mockInterview);
            default:
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数错误");
        }
    }

    /**
     * 处理 ai 结束事件
     * @param mockInterview
     * @return
     */
    private String handleChatEndEvent(MockInterview mockInterview) {
        // 构造消息列表，首先要获取消息记录
        String historyMessage = mockInterview.getMessages();
        List<MockInterviewChatMessage> historyMessageList = JSONUtil.parseArray(historyMessage).toList(MockInterviewChatMessage.class);
        List<ChatMessage> chatMessages = transformToChatMessage(historyMessageList);
        // 构造用户结束消息
        String endUserPrompt = "结束";
        final ChatMessage endUserMessage = ChatMessage.builder().role(ChatMessageRole.USER).content(endUserPrompt).build();
        chatMessages.add(endUserMessage);
        // 调用 AI 获取结果
        String endAnswer = aiManager.doChat(chatMessages);
        final ChatMessage endAssistantMessage = ChatMessage.builder().role(ChatMessageRole.ASSISTANT).content(endAnswer).build();
        chatMessages.add(endAssistantMessage);

        // 保存消息记录，并更新状态
        List<MockInterviewChatMessage> newChatMessageList = transformFromChatMessage(chatMessages);
        String newJsonStr = JSONUtil.toJsonStr(newChatMessageList);

        MockInterview newUpdateMockInterview = new MockInterview();
        newUpdateMockInterview.setStatus(MockInterviewStatusEnum.ENDED.getValue());
        newUpdateMockInterview.setId(mockInterview.getId());
        newUpdateMockInterview.setMessages(newJsonStr);
        boolean newResult = this.updateById(newUpdateMockInterview);
        ThrowUtils.throwIf(!newResult, ErrorCode.SYSTEM_ERROR, "更新失败");
        return endAnswer;
    }

    /**
     * 处理 ai 对话事件
     * @param mockInterviewEventRequest
     * @param mockInterview
     * @return
     */
    private String handleChatMessageEvent(MockInterviewEventRequest mockInterviewEventRequest, MockInterview mockInterview) {
        // 构造消息列表，首先要获取消息记录
        String message = mockInterviewEventRequest.getMessage();
        String historyMessage = mockInterview.getMessages();
        List<MockInterviewChatMessage> historyMessageList = JSONUtil.parseArray(historyMessage).toList(MockInterviewChatMessage.class);
        // 将数据库中的消息转换为 AI 的 ChatMessage list 类型
        List<ChatMessage> chatMessages = transformToChatMessage(historyMessageList);
        final ChatMessage chatUserMessage = ChatMessage.builder().role(ChatMessageRole.USER).content(message).build();
        chatMessages.add(chatUserMessage);
        // 调用 AI 获取结果
        String chatAnswer = aiManager.doChat(chatMessages);
        final ChatMessage chatAssistantMessage = ChatMessage.builder().role(ChatMessageRole.ASSISTANT).content(chatAnswer).build();
        chatMessages.add(chatAssistantMessage);
        // 保存消息记录
        List<MockInterviewChatMessage> newChatMessageList = transformFromChatMessage(chatMessages);
        String newJsonStr = JSONUtil.toJsonStr(newChatMessageList);
        MockInterview newUpdateMockInterview = new MockInterview();
        newUpdateMockInterview.setId(mockInterview.getId());
        newUpdateMockInterview.setMessages(newJsonStr);
        // 如果 AI 主动结束面试，需要更新状态
        if (chatAnswer.contains("【面试结束】")) {
            newUpdateMockInterview.setStatus(MockInterviewStatusEnum.ENDED.getValue());
        }
        boolean newResult = this.updateById(newUpdateMockInterview);
        ThrowUtils.throwIf(!newResult, ErrorCode.SYSTEM_ERROR, "更新失败");
        return chatAnswer;
    }

    /**
     * 处理 ai 开始对话
     * @param mockInterview
     * @return
     */
    private String handleChatStartEvent(MockInterview mockInterview) {
        // 编写 AI prompt
        String systemPrompt = String.format("你是一位严厉的程序员面试官，我是候选人，来应聘 %s 的 %s 岗位，面试难度为 %s。请你向我依次提出问题（最多 20 个问题），我也会依次回复。在这期间请完全保持真人面试官的口吻，比如适当引导学员、或者表达出你对学员回答的态度。\n" +
                        "必须满足如下要求：\n" +
                        "1. 当学员回复 “开始” 时，你要正式开始面试\n" +
                        "2. 当学员表示希望 “结束面试” 时，你要结束面试\n" +
                        "3. 此外，当你觉得这场面试可以结束时（比如候选人回答结果较差、不满足工作年限的招聘需求、或者候选人态度不礼貌），必须主动提出面试结束，不用继续询问更多问题了。并且要在回复中包含字符串【面试结束】\n" +
                        "4. 面试结束后，应该给出候选人整场面试的表现和总结。\n",
                mockInterview.getWorkExperience(), mockInterview.getJobPosition(), mockInterview.getDifficulty());
        // 构造消息列表
        String userPrompt = "开始";
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage systemMessage = ChatMessage.builder().role(ChatMessageRole.SYSTEM).content(systemPrompt).build();
        final ChatMessage userMessage = ChatMessage.builder().role(ChatMessageRole.USER).content(userPrompt).build();
        messages.add(systemMessage);
        messages.add(userMessage);
        // 调用 AI 获取结果
        String answer = aiManager.doChat(messages);
        final ChatMessage assistantMessage = ChatMessage.builder().role(ChatMessageRole.ASSISTANT).content(answer).build();
        messages.add(assistantMessage);
        // 保存消息记录，并更新状态
        List<MockInterviewChatMessage> chatMessageList = transformFromChatMessage(messages);
        String jsonStr = JSONUtil.toJsonStr(chatMessageList);
        // 操作数据库进行更新
        MockInterview updateMockInterview = new MockInterview();
        updateMockInterview.setStatus(MockInterviewStatusEnum.IN_PROGRESS.getValue());
        updateMockInterview.setId(mockInterview.getId());
        updateMockInterview.setMessages(jsonStr);
        boolean result = this.updateById(updateMockInterview);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新失败");
        return answer;
    }

    /**
     * 消息记录对象转换
     * @param chatMessageList
     * @return
     */
    List<MockInterviewChatMessage> transformFromChatMessage(List<ChatMessage> chatMessageList) {
        return chatMessageList.stream().map(chatMessage -> {
            MockInterviewChatMessage mockInterviewChatMessage = new MockInterviewChatMessage();
            mockInterviewChatMessage.setRole(chatMessage.getRole().value());
            mockInterviewChatMessage.setMessage(chatMessage.getContent().toString());
            return mockInterviewChatMessage;
        }).collect(Collectors.toList());
    }

    /**
     * 消息记录对象转换
     * @param chatMessageList
     * @return
     */
    List<ChatMessage> transformToChatMessage(List<MockInterviewChatMessage> chatMessageList) {
        return chatMessageList.stream().map(chatMessage -> {
            ChatMessage tempChatmessage = ChatMessage.builder().
                    role(ChatMessageRole.valueOf(chatMessage.getRole().toUpperCase())).
                    content(chatMessage.getMessage()).build();

//            MockInterviewChatMessage mockInterviewChatMessage = new MockInterviewChatMessage();
//            tempChatmessage.setRole(chatMessage.getRole());
//            mockInterviewChatMessage.setMessage(tempChatmessage.getContent().toString());
            return tempChatmessage;
        }).collect(Collectors.toList());
    }

}





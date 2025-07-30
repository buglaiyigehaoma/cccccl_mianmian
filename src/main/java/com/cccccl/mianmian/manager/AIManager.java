package com.cccccl.mianmian.manager;

import cn.hutool.core.collection.CollUtil;
import com.cccccl.mianmian.common.ErrorCode;
import com.cccccl.mianmian.exception.BusinessException;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用的 AI 调用类
 */

@Service
public class AIManager {
    @Resource
    private ArkService arkService;
    private final String DEFAULT_MODEL = "deepseek-v3-250324";

    public String doChat(String userPrompt) {
        return doChat("", userPrompt, DEFAULT_MODEL);
    }

    /**
     * AI 调用接口，返回响应字符串
     * @param systemPrompt
     * @param userPrompt
     * @param model
     * @return
     */
    public String doChat(String systemPrompt, String userPrompt, String model) {
        final List<ChatMessage> messages = new ArrayList<>();
//        构造消息列表
        final ChatMessage systemMessage = ChatMessage.builder().role(ChatMessageRole.SYSTEM).content(systemPrompt).build();
        final ChatMessage userMessage = ChatMessage.builder().role(ChatMessageRole.USER).content(userPrompt).build();
        messages.add(systemMessage);
        messages.add(userMessage);
//        构造请求
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                // 指定您创建的方舟推理接入点 ID，此处已帮您修改为您的推理接入点 ID
//                .model("deepseek-v3-250324")
                .model(model)
                .messages(messages)
                .build();
//          调用接口发送请求
        List<ChatCompletionChoice> choices = arkService.createChatCompletion(chatCompletionRequest).getChoices();
        if(CollUtil.isNotEmpty(choices)) {
            return (String) choices.get(0).getMessage().getContent();
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 调用失败 没有返回结果");
//                .forEach(choice -> System.out.println(choice.getMessage().getContent()));

//        arkService.shutdownExecutor();
    }
}

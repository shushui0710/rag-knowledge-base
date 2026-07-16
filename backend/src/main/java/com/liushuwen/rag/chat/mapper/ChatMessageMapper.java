package com.liushuwen.rag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liushuwen.rag.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}

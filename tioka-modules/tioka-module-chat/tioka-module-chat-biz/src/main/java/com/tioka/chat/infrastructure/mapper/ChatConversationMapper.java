package com.tioka.chat.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversationDO> {}

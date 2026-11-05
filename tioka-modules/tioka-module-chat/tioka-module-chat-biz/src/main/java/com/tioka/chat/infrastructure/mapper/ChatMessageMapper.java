package com.tioka.chat.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.chat.infrastructure.persistence.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {}

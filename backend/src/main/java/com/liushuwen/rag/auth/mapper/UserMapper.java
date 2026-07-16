package com.liushuwen.rag.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liushuwen.rag.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}

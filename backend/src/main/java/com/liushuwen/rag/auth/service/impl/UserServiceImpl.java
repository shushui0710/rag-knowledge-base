package com.liushuwen.rag.auth.service.impl;

import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.mapper.UserMapper;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public User register(String username, String password) {
        // TODO: 实现用户注册
        // 1. 检查用户名是否重复
        // 2. 密码加密（BCrypt）
        // 3. 存入数据库
        log.info("注册用户: {}", username);
        throw new BusinessException("用户注册功能待实现 - 第6周开发");
    }

    @Override
    public User login(String username, String password) {
        // TODO: 实现用户登录
        // 1. 查询用户
        // 2. 校验密码（BCrypt）
        // 3. 生成JWT Token
        log.info("登录用户: {}", username);
        throw new BusinessException("用户登录功能待实现 - 第6周开发");
    }

    @Override
    public User getCurrentUser() {
        // TODO: 从JWT Token解析当前用户
        throw new BusinessException("获取当前用户功能待实现 - 第6周开发");
    }
}

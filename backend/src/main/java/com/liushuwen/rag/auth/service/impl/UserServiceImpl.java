package com.liushuwen.rag.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.mapper.UserMapper;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现：注册、登录、获取当前用户，核心是基于 BCrypt 的密码校验。
 * 【设计要点】BCrypt 慢哈希：密文内嵌随机盐（$2a$10$...），同密码每次结果不同，抗彩虹表与暴力破解，优于 MD5+盐
 * 【常见问题】matches(明文,密文) 怎么验证？——从密文提取盐值重新加密明文再比较；登录失败为何统一报错？——不区分"用户不存在/密码错"，防攻击者枚举有效账号
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    /**
     * BCrypt 密码编码器：无状态、线程安全，随 Service 单例复用。
     * 【设计要点】慢哈希成本因子：构造参数 10 = 2^10 轮迭代，约 100ms 加密一次，值越大越安全越慢，权衡抗暴力破解与性能
     * 【常见问题】为何用实例字段而非每次 new？——对象线程安全可复用，跟随单例生命周期，避免重复构造开销
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Override
    public User register(String username, String password) {
        // 功能：按用户名查重，命中则抛业务异常｜要点：注册唯一性约束
        LambdaQueryWrapper<User> wrapper =new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User existing = userMapper.selectOne(wrapper);
        if(existing !=null){
            throw new BusinessException("用户名已存在");
        }
        // 功能：BCrypt 加密密码（密文内嵌随机盐）｜要点：慢哈希抗暴力破解，优于明文/MD5
        String encodedPassword = passwordEncoder.encode(password);
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setNickname(username);
        // 功能：注册一律写普通角色｜要点：提权只能由运维在库侧显式 UPDATE，注册接口不可自助获得运维权限
        user.setRole(User.ROLE_USER);
        userMapper.insert(user);
        // 常见问题：密码为什么不能明文入库？→ 数据库一旦泄漏即全员裸奔，哈希不可逆也无法挽回
        log.info("注册用户: {}", username);
        return user;


    }

    @Override
    public User login(String username, String password) {
        // 功能：按用户名查用户｜要点：登录只查库不验密，失败提示统一防用户名枚举
        LambdaQueryWrapper<User> wrapper =new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User user = userMapper.selectOne(wrapper);
        if(user == null){
            throw new BusinessException("用户名或密码错误");
        }
        // 功能：BCrypt matches 验证密码（从密文提取盐值重加密比较）｜要点：统一报错防用户枚举
        if(!passwordEncoder.matches(password,user.getPassword())){
            throw new BusinessException("用户名或密码错误");
        }
        // 常见问题：为何不区分"用户不存在/密码错误"？→ 统一提示避免攻击者枚举有效账号
        log.info("登录用户: {}", username);
        return user;


    }

    @Override
    public User getCurrentUser() {
        // 功能：从 UserContext(ThreadLocal) 取 userId 查库返回当前用户｜要点：跨层传参不污染方法签名
        Long userId=UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if(user == null){
            throw new BusinessException("用户不存在");
        }
        // 常见问题：为何 setPassword(null)？→ 加密密码也属敏感信息，不回传前端
        user.setPassword(null);
        return user;
        


    }
}

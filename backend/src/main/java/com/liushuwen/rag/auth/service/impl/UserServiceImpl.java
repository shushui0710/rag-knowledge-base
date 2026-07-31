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
 * 用户服务实现 - 认证核心逻辑
 *
 * 依赖说明：
 *   - UserMapper: 操作 user 表
 *   - BCryptPasswordEncoder: 密码加密/验证（spring-security-crypto 提供）
 *
 * 注意：JWT Token 的生成在 AuthController 中调用 JwtUtil，
 *   本类只负责"验证密码"这个业务逻辑。
 *
 * 面试考点：
 *   Q: 为什么用 BCrypt 不用 MD5？
 *   A: BCrypt 自带随机盐值，同一密码每次加密结果不同，防彩虹表攻击。
 *      MD5 是确定性哈希，同一输入永远同一输出，容易被预计算破解。
 *
 *   Q: BCrypt 验证密码时怎么知道用哪个盐值？
 *   A: BCrypt 密文本身包含盐值（格式：$2a$10$salt...hash），
 *      matches() 会从密文中提取盐值，用相同盐值重新加密输入密码，比较结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    /**
     * BCrypt 密码编码器
     *
     * 为什么用 @Bean 注入的实例字段而不是 static？
     *   BCryptPasswordEncoder 是无状态的线程安全对象，构造一次即可复用。
     *   放在实例字段上，跟随 Service 单例生命周期，不需要每次方法调用都 new。
     *
     * strength=10 是计算成本因子（2^10=1024轮迭代），值越大越安全但越慢。
     * 10 是业界默认值，大约 100ms 加密一次。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Override
    public User register(String username, String password) {
        // ============================================================
        // TODO 1（⭐⭐ 难度）：实现用户注册
        //
        // 步骤：
        //   1. 检查用户名是否重复
        //      用 LambdaQueryWrapper 按 username 查询，如果查到了说明已存在
        //      提示：
        //        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        //        wrapper.eq(User::getUsername, username);
        //        User existing = userMapper.selectOne(wrapper);
        //        if (existing != null) → 抛 BusinessException("用户名已存在")
        //
        //   2. 密码加密
        //      String encodedPassword = passwordEncoder.encode(password);
        //
        //   3. 构建 User 对象并存入数据库
        //      User user = new User();
        //      user.setUsername(username);
        //      user.setPassword(encodedPassword);
        //      user.setNickname(username);  // 默认昵称=用户名
        //      userMapper.insert(user);
        //      log.info("注册成功: {}", username);
        //      return user;
        //
        // 面试考点：
        //   - 为什么密码不能明文存数据库？— 数据库泄漏直接暴露所有密码
        //   - BCrypt 的 strength 参数？— 计算成本因子，值越大越安全但越慢
        // ============================================================
        LambdaQueryWrapper<User> wrapper =new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User existing = userMapper.selectOne(wrapper);
        if(existing !=null){
            throw new BusinessException("用户名已存在");
        }
        String encodedPassword = passwordEncoder.encode(password);
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setNickname(username);
        userMapper.insert(user);
        log.info("注册用户: {}", username);
        return user;


    }

    @Override
    public User login(String username, String password) {
        // ============================================================
        // TODO 2（⭐⭐ 难度）：实现用户登录（验证密码）
        //
        // 步骤：
        //   1. 按 username 查询用户
        //      提示：
        //        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        //        wrapper.eq(User::getUsername, username);
        //        User user = userMapper.selectOne(wrapper);
        //        if (user == null) → 抛 BusinessException("用户名或密码错误")
        //
        //   2. BCrypt 验证密码
        //      提示：
        //        if (!passwordEncoder.matches(password, user.getPassword())) {
        //            throw new BusinessException("用户名或密码错误");
        //        }
        //
        //   3. 登录成功，返回 User 对象
        //      注意：token 由 AuthController 生成，这里只管验证密码
        //      log.info("登录成功: {}", username);
        //      return user;
        //
        // 面试考点：
        //   - 为什么错误信息不区分"用户不存在"和"密码错误"？
        //     防止攻击者通过错误信息枚举有效用户名
        //   - matches(明文, 密文) 的原理？
        //     从密文提取盐值 → 用盐值加密明文 → 比较结果
        // ============================================================
        LambdaQueryWrapper<User> wrapper =new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User user = userMapper.selectOne(wrapper);
        if(user == null){
            throw new BusinessException("用户名或密码错误");
        }
        if(!passwordEncoder.matches(password,user.getPassword())){
            throw new BusinessException("用户名或密码错误");
        }
        log.info("登录用户: {}", username);
        return user;


    }

    @Override
    public User getCurrentUser() {
        // ============================================================
        // TODO 3（⭐ 难度）：从 UserContext 获取当前用户
        //
        // 步骤：
        //   1. 从 UserContext 获取 userId
        //      Long userId = UserContext.getUserId();
        //
        //   2. 查数据库返回 User
        //      User user = userMapper.selectById(userId);
        //      if (user == null) → 抛 BusinessException("用户不存在")
        //      user.setPassword(null);  // 不返回密码
        //      return user;
        //
        // 面试考点：
        //   - UserContext 里存的 userId 从哪来的？
        //     JwtInterceptor 在 preHandle 中从 JWT 解析出来，存入 ThreadLocal
        //   - 为什么要 user.setPassword(null)？
        //     密码是敏感信息，即使加密了也不应该返回给前端
        // ============================================================
        Long userId=UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if(user == null){
            throw new BusinessException("用户不存在");
        }
        user.setPassword(null);
        return user;
        


    }
}

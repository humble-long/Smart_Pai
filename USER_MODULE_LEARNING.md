# 派聪明用户管理模块深度学习指南

## 📚 目录
1. [模块概述](#模块概述)
2. [核心实体设计](#核心实体设计)
3. [认证与授权机制](#认证与授权机制)
4. [用户注册流程](#用户注册流程)
5. [用户登录流程](#用户登录流程)
6. [JWT Token 机制](#jwt-token-机制)
7. [组织标签体系](#组织标签体系)
8. [权限控制](#权限控制)
9. [管理员功能](#管理员功能)
10. [代码实战解析](#代码实战解析)
11. [最佳实践](#最佳实践)

---

## 模块概述

### 🎯 功能范围
用户管理模块是派聪明系统的基础模块，负责：
- ✅ **用户注册与登录**：支持账号创建和身份验证
- ✅ **JWT 认证**：基于 Token 的无状态认证
- ✅ **角色管理**：USER（普通用户）、ADMIN（管理员）两种角色
- ✅ **组织标签体系**：支持层级组织结构和数据隔离
- ✅ **权限控制**：基于角色和组织标签的细粒度权限
- ✅ **Token 刷新**：自动无感知的 Token 刷新机制

### 📂 模块文件结构
```
src/main/java/com/yizhaoqi/smartpai/
├── model/
│   ├── User.java                      # 用户实体
│   └── OrganizationTag.java           # 组织标签实体
├── controller/
│   ├── UserController.java            # 用户接口控制器
│   ├── AuthController.java            # 认证接口控制器
│   └── AdminController.java           # 管理员接口控制器
├── service/
│   ├── UserService.java               # 用户业务逻辑
│   ├── CustomUserDetailsService.java  # Spring Security 用户详情
│   ├── TokenCacheService.java         # Token 缓存服务
│   └── OrgTagCacheService.java        # 组织标签缓存服务
├── repository/
│   ├── UserRepository.java            # 用户数据访问
│   └── OrganizationTagRepository.java # 组织标签数据访问
├── config/
│   ├── SecurityConfig.java            # Spring Security 配置
│   ├── JwtAuthenticationFilter.java   # JWT 认证过滤器
│   └── OrgTagAuthorizationFilter.java # 组织标签授权过滤器
└── utils/
    ├── JwtUtils.java                  # JWT 工具类
    └── PasswordUtil.java              # 密码加密工具类
```

---

## 核心实体设计

### 1. User 实体（用户模型）

```java
@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 用户ID（数据库主键）

    @Column(nullable = false, unique = true)
    private String username;            // 用户名（唯一）

    @Column(nullable = false)
    private String password;            // 加密密码

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;                  // 用户角色（USER/ADMIN）

    @Column(name = "org_tags")
    private String orgTags;             // 组织标签（逗号分隔）

    @Column(name = "primary_org")
    private String primaryOrg;          // 主组织标签

    @CreationTimestamp
    private LocalDateTime createdAt;    // 创建时间

    @UpdateTimestamp
    private LocalDateTime updatedAt;    // 更新时间

    public enum Role {
        USER,   // 普通用户
        ADMIN   // 管理员
    }
}
```

#### 🔑 字段说明

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| **id** | Long | 用户唯一标识（自增主键） | 1001 |
| **username** | String | 用户名，全局唯一 | "zhangsan" |
| **password** | String | BCrypt 加密后的密码 | "$2a$10$..." |
| **role** | Enum | 用户角色 | USER / ADMIN |
| **orgTags** | String | 用户所属组织标签，多个用逗号分隔 | "PRIVATE_zhangsan,tech,backend" |
| **primaryOrg** | String | 用户的主组织标签 | "PRIVATE_zhangsan" |
| **createdAt** | LocalDateTime | 账号创建时间（自动生成） | 2026-01-04 10:30:00 |
| **updatedAt** | LocalDateTime | 账号更新时间（自动更新） | 2026-01-04 15:20:00 |

#### 💡 设计亮点
1. **@UniqueConstraint**：确保用户名唯一性，避免重复注册
2. **@Enumerated(EnumType.STRING)**：角色以字符串形式存储，便于阅读和维护
3. **@CreationTimestamp/@UpdateTimestamp**：自动管理时间戳，无需手动设置
4. **orgTags 逗号分隔**：灵活的多标签设计，支持用户归属多个组织

---

### 2. OrganizationTag 实体（组织标签模型）

```java
@Data
@Entity
@Table(name = "organization_tags")
public class OrganizationTag {
    @Id
    @Column(name = "tag_id")
    private String tagId;               // 标签唯一标识

    @Column(nullable = false)
    private String name;                // 标签名称

    @Column(columnDefinition = "TEXT")
    private String description;         // 标签描述

    @Column(name = "parent_tag")
    private String parentTag;           // 父标签ID（支持层级结构）

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;             // 创建者

    @CreationTimestamp
    private LocalDateTime createdAt;    // 创建时间

    @UpdateTimestamp
    private LocalDateTime updatedAt;    // 更新时间
}
```

#### 🌲 层级组织结构示例
```
tech (技术部)
├── backend (后端组)
│   ├── java (Java 小组)
│   └── python (Python 小组)
├── frontend (前端组)
│   ├── vue (Vue 小组)
│   └── react (React 小组)
└── ai (AI 组)
    └── nlp (NLP 小组)
```

#### 💡 设计亮点
1. **parentTag 自引用**：支持无限层级的组织树
2. **灵活的权限继承**：用户属于子标签自动拥有父标签权限
3. **私人标签机制**：每个用户注册时自动创建 `PRIVATE_username` 标签
4. **多对一关联**：记录标签创建者，便于审计

---

## 认证与授权机制

### 整体架构
```
┌─────────────┐
│   用户请求   │
└──────┬──────┘
       │
       ▼
┌──────────────────────────────┐
│  JwtAuthenticationFilter     │  ← 1. 解析并验证 JWT Token
│  - 提取 Token                │  ← 2. 自动刷新即将过期的 Token
│  - 验证有效性                 │  ← 3. 设置 Spring Security 上下文
│  - 刷新机制                   │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  OrgTagAuthorizationFilter   │  ← 4. 检查组织标签权限
│  - 验证资源访问权限           │  ← 5. 确保数据隔离
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  SecurityConfig              │  ← 6. 基于角色的 URL 访问控制
│  - URL 权限配置              │
│  - 角色授权                  │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  Controller 处理业务逻辑     │
└──────────────────────────────┘
```

### Spring Security 配置解析

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())  // 禁用CSRF（无状态API）
            .authorizeHttpRequests(authorize -> authorize
                // 公开接口：无需认证
                .requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
                .requestMatchers("/chat/**", "/ws/**").permitAll()  // WebSocket
                
                // 普通用户接口：需要 USER 或 ADMIN 角色
                .requestMatchers("/api/v1/upload/**", "/api/v1/documents/**")
                    .hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/search/**")
                    .hasAnyRole("USER", "ADMIN")
                
                // 管理员专属接口：仅 ADMIN
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                
                // 其他请求需要认证
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)  // 无状态会话
            )
            // 添加自定义过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class);
        
        return http.build();
    }
}
```

#### 🔐 权限级别说明

| 权限级别 | 说明 | 示例接口 |
|---------|------|---------|
| **permitAll()** | 完全公开，无需认证 | `/api/v1/users/register`<br>`/api/v1/users/login` |
| **hasAnyRole("USER", "ADMIN")** | 需要登录，普通用户和管理员都可访问 | `/api/v1/upload/**`<br>`/api/search/**` |
| **hasRole("ADMIN")** | 仅管理员可访问 | `/api/v1/admin/**` |
| **authenticated()** | 需要认证（任何已登录用户） | 其他未明确配置的接口 |

---

## 用户注册流程

### 时序图
```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Controller as UserController
    participant Service as UserService
    participant Repo as UserRepository
    participant OrgRepo as OrganizationTagRepository
    participant Cache as OrgTagCacheService
    participant DB as MySQL
    
    Client->>Controller: POST /api/v1/users/register<br>{username, password}
    Controller->>Controller: 参数验证
    Controller->>Service: registerUser(username, password)
    
    Service->>Repo: findByUsername(username)
    Repo->>DB: SELECT * FROM users
    DB-->>Repo: 返回查询结果
    Repo-->>Service: Optional<User>
    
    alt 用户名已存在
        Service-->>Controller: 抛出 CustomException("Username already exists")
        Controller-->>Client: 400 Bad Request
    else 用户名可用
        Service->>Service: 加密密码 BCrypt
        Service->>Repo: save(user)
        Repo->>DB: INSERT INTO users
        DB-->>Repo: 返回用户ID
        
        Service->>OrgRepo: 创建私人组织标签<br>PRIVATE_username
        OrgRepo->>DB: INSERT INTO organization_tags
        
        Service->>Repo: 更新用户 orgTags 和 primaryOrg
        Repo->>DB: UPDATE users
        
        Service->>Cache: 缓存组织标签到 Redis
        Cache-->>Service: 缓存成功
        
        Service-->>Controller: 注册成功
        Controller-->>Client: 200 OK<br>{"message": "User registered successfully"}
    end
```

### 核心代码解析

```java
@Transactional
public void registerUser(String username, String password) {
    // 1. 检查用户名是否已存在
    if (userRepository.findByUsername(username).isPresent()) {
        throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
    }
    
    // 2. 确保默认组织标签存在（系统初始化）
    ensureDefaultOrgTagExists();
    
    // 3. 创建用户对象
    User user = new User();
    user.setUsername(username);
    user.setPassword(PasswordUtil.encode(password));  // BCrypt 加密
    user.setRole(User.Role.USER);                     // 默认普通用户
    
    // 4. 先保存用户以生成ID
    userRepository.save(user);
    
    // 5. 创建用户的私人组织标签
    String privateTagId = "PRIVATE_" + username;
    createPrivateOrgTag(privateTagId, username, user);
    
    // 6. 分配私人组织标签
    user.setOrgTags(privateTagId);
    user.setPrimaryOrg(privateTagId);
    userRepository.save(user);
    
    // 7. 缓存组织标签信息到 Redis
    orgTagCacheService.cacheUserOrgTags(username, List.of(privateTagId));
    orgTagCacheService.cacheUserPrimaryOrg(username, privateTagId);
    
    logger.info("User registered successfully: {}", username);
}

/**
 * 创建用户的私人组织标签
 */
private void createPrivateOrgTag(String privateTagId, String username, User owner) {
    if (!organizationTagRepository.existsByTagId(privateTagId)) {
        OrganizationTag privateTag = new OrganizationTag();
        privateTag.setTagId(privateTagId);
        privateTag.setName(username + "的私人空间");
        privateTag.setDescription("用户的私人组织标签，仅用户本人可访问");
        privateTag.setCreatedBy(owner);
        
        organizationTagRepository.save(privateTag);
        logger.info("Private organization tag created: {}", privateTagId);
    }
}
```

### 💡 核心设计亮点

1. **自动创建私人空间**
   - 每个用户注册时自动创建 `PRIVATE_username` 标签
   - 作为用户的主组织标签（primaryOrg）
   - 确保用户的私有文档与他人隔离

2. **密码安全**
   - 使用 BCrypt 加密算法（Spring Security 推荐）
   - 自动加盐（salt），每次加密结果不同
   - 单向加密，无法解密

3. **事务管理**
   - `@Transactional` 确保数据一致性
   - 如果创建组织标签失败，用户创建也会回滚

4. **Redis 缓存**
   - 提前缓存组织标签信息，减少后续查询
   - 提升权限验证性能

---

## 用户登录流程

### 时序图
```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Controller as UserController
    participant Service as UserService
    participant JWT as JwtUtils
    participant Cache as TokenCacheService
    participant Redis as Redis
    participant DB as MySQL
    
    Client->>Controller: POST /api/v1/users/login<br>{username, password}
    Controller->>Controller: 参数验证
    Controller->>Service: authenticateUser(username, password)
    
    Service->>DB: 查询用户信息
    DB-->>Service: User 对象
    Service->>Service: 验证密码 BCrypt.matches()
    
    alt 密码错误
        Service-->>Controller: 抛出 CustomException(401)
        Controller-->>Client: 401 Unauthorized
    else 密码正确
        Service-->>Controller: 返回 username
        
        Controller->>JWT: generateToken(username)
        JWT->>DB: 查询用户角色和组织标签
        DB-->>JWT: User 详情
        JWT->>JWT: 构建 JWT Claims<br>(username, role, userId, orgTags)
        JWT->>JWT: 签名生成 Token
        JWT->>Cache: 缓存 Token 到 Redis
        Cache->>Redis: SET token:tokenId username
        JWT-->>Controller: accessToken
        
        Controller->>JWT: generateRefreshToken(username)
        JWT-->>Controller: refreshToken
        
        Controller-->>Client: 200 OK<br>{<br>  "token": "eyJhbGc...",<br>  "refreshToken": "eyJhbGc..."<br>}
    end
```

### 核心代码解析

#### 1. 用户认证（UserService）

```java
public String authenticateUser(String username, String password) {
    // 1. 查询用户
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new CustomException(
            "Invalid username or password", 
            HttpStatus.UNAUTHORIZED
        ));
    
    // 2. 验证密码
    if (!PasswordUtil.matches(password, user.getPassword())) {
        throw new CustomException(
            "Invalid username or password", 
            HttpStatus.UNAUTHORIZED
        );
    }
    
    // 3. 认证成功，返回用户名
    return user.getUsername();
}
```

#### 2. 生成 JWT Token（JwtUtils）

```java
public String generateToken(String username) {
    // 1. 获取用户信息
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
    
    // 2. 生成唯一的 tokenId
    String tokenId = UUID.randomUUID().toString();
    long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;  // 1小时
    
    // 3. 构建 JWT Claims（负载）
    Map<String, Object> claims = new HashMap<>();
    claims.put("tokenId", tokenId);              // Token 唯一标识
    claims.put("role", user.getRole().name());   // 用户角色
    claims.put("userId", user.getId().toString());  // 用户ID
    
    // 添加组织标签信息
    if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
        claims.put("orgTags", user.getOrgTags());
    }
    if (user.getPrimaryOrg() != null) {
        claims.put("primaryOrg", user.getPrimaryOrg());
    }
    
    // 4. 生成 JWT Token
    String token = Jwts.builder()
        .setClaims(claims)
        .setSubject(username)
        .setExpiration(new Date(expireTime))
        .signWith(getSigningKey(), SignatureAlgorithm.HS256)
        .compact();
    
    // 5. 缓存 Token 到 Redis（1小时）
    tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
    
    logger.info("Token generated for user: {}, tokenId: {}", username, tokenId);
    return token;
}
```

#### 3. 登录接口（UserController）

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody UserRequest request) {
    try {
        // 1. 参数验证
        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", "Username and password cannot be empty"));
        }
        
        // 2. 用户认证
        String username = userService.authenticateUser(
            request.username(), 
            request.password()
        );
        
        if (username == null) {
            return ResponseEntity.status(401)
                .body(Map.of("code", 401, "message", "Invalid credentials"));
        }
        
        // 3. 生成 Token
        String token = jwtUtils.generateToken(username);
        String refreshToken = jwtUtils.generateRefreshToken(username);
        
        // 4. 返回成功响应
        return ResponseEntity.ok(Map.of(
            "code", 200, 
            "message", "Login successful", 
            "data", Map.of(
                "token", token,
                "refreshToken", refreshToken
            )
        ));
    } catch (CustomException e) {
        return ResponseEntity.status(e.getStatus())
            .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
    }
}
```

### 🔐 密码加密机制

#### BCrypt 算法特点
```java
// 加密示例
String rawPassword = "MyPassword123";
String encodedPassword = PasswordUtil.encode(rawPassword);
// 结果：$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy

// 同样的密码，每次加密结果都不同（因为随机 salt）
String encoded1 = PasswordUtil.encode("MyPassword123");
String encoded2 = PasswordUtil.encode("MyPassword123");
// encoded1 != encoded2 （但都能验证成功）

// 验证密码
boolean matches = PasswordUtil.matches("MyPassword123", encodedPassword);
// 返回 true
```

#### 为什么使用 BCrypt？
1. **自动加盐**：每次加密自动生成随机盐值
2. **计算复杂**：可调节计算强度（默认 10 轮），防暴力破解
3. **单向加密**：无法从密文反推明文
4. **Spring Security 推荐**：官方推荐的密码加密方式

---

## JWT Token 机制

### JWT 结构解析

JWT（JSON Web Token）由三部分组成，用 `.` 分隔：
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ6aGFuZ3NhbiIsInJvbGUiOiJVU0VSIiwiZXhwIjoxNjQwOTk1MjAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
│                                      │                                                                                      │
└─────────── Header ──────────────────┴─────────────────────────── Payload ─────────────────────────────────────────────────┴──────── Signature ──────
```

#### 1. Header（头部）
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

#### 2. Payload（负载）- 派聪明的 Claims
```json
{
  "sub": "zhangsan",                    // 用户名
  "tokenId": "550e8400-e29b-41d4-a716-446655440000",  // Token唯一标识
  "role": "USER",                       // 用户角色
  "userId": "1001",                     // 用户ID
  "orgTags": "PRIVATE_zhangsan,tech",   // 组织标签
  "primaryOrg": "PRIVATE_zhangsan",     // 主组织
  "exp": 1640995200                     // 过期时间（Unix时间戳）
}
```

#### 3. Signature（签名）
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret_key
)
```

### Token 缓存机制（Redis）

```java
// Redis 缓存结构
Key: "token:{tokenId}"
Value: JSON字符串
{
  "userId": "1001",
  "username": "zhangsan",
  "expireTime": 1640995200000
}
TTL: 3600 秒（1小时）

// 示例
Key: "token:550e8400-e29b-41d4-a716-446655440000"
Value: {"userId":"1001","username":"zhangsan","expireTime":1640995200000}
```

#### 为什么要用 Redis 缓存？

| 优势 | 说明 |
|------|------|
| **性能优化** | 避免每次请求都查询数据库验证用户 |
| **Token 失效控制** | 可以主动删除 Redis 中的 Token，实现强制登出 |
| **过期管理** | 利用 Redis 的 TTL 自动清理过期 Token |
| **分布式支持** | 多服务器共享 Token 验证状态 |

### Token 自动刷新机制

#### 刷新策略
```java
// 配置参数
EXPIRATION_TIME = 3600000;           // 1小时
REFRESH_THRESHOLD = 300000;          // 5分钟
REFRESH_WINDOW = 600000;             // 10分钟

// 刷新逻辑
if (token 剩余时间 < 5分钟) {
    自动刷新 Token
    响应头返回 New-Token
}

// 宽限期
if (token 已过期 && 过期时间 < 10分钟) {
    允许刷新
}
```

#### 前端配合流程
```javascript
// Axios 响应拦截器
axios.interceptors.response.use(response => {
  // 检查响应头是否有新 Token
  const newToken = response.headers['new-token'];
  if (newToken) {
    // 更新本地存储的 Token
    localStorage.setItem('token', newToken);
    console.log('Token auto-refreshed');
  }
  return response;
});
```

#### 核心实现代码

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) {
        String token = extractToken(request);
        if (token != null) {
            String newToken = null;
            String username = null;
            
            // 1. Token 有效，检查是否需要预刷新
            if (jwtUtils.validateToken(token)) {
                if (jwtUtils.shouldRefreshToken(token)) {  // 剩余时间 < 5分钟
                    newToken = jwtUtils.refreshToken(token);
                    logger.info("Token auto-refreshed proactively");
                }
                username = jwtUtils.extractUsernameFromToken(token);
            } 
            // 2. Token 过期，检查是否在宽限期内
            else {
                if (jwtUtils.canRefreshExpiredToken(token)) {  // 过期 < 10分钟
                    newToken = jwtUtils.refreshToken(token);
                    logger.info("Expired token refreshed within grace period");
                    username = jwtUtils.extractUsernameFromToken(newToken);
                }
            }
            
            // 3. 通过响应头返回新 Token
            if (newToken != null) {
                response.setHeader("New-Token", newToken);
            }
            
            // 4. 设置 Spring Security 上下文
            if (username != null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()
                    );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        
        filterChain.doFilter(request, response);
    }
}
```

### 💡 Token 刷新最佳实践

#### 1. 双 Token 机制
```
Access Token (短期，1小时)
└── 用于日常 API 调用

Refresh Token (长期，7天)
└── 用于刷新 Access Token
```

#### 2. 刷新接口
```java
@PostMapping("/api/v1/auth/refreshToken")
public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
    // 验证 Refresh Token
    if (!jwtUtils.validateRefreshToken(request.refreshToken())) {
        return ResponseEntity.status(401)
            .body(Map.of("code", 401, "message", "Invalid refresh token"));
    }
    
    // 提取用户名
    String username = jwtUtils.extractUsernameFromToken(request.refreshToken());
    
    // 生成新的 Token 对
    String newToken = jwtUtils.generateToken(username);
    String newRefreshToken = jwtUtils.generateRefreshToken(username);
    
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "data", Map.of(
            "token", newToken,
            "refreshToken", newRefreshToken
        )
    ));
}
```

---

## 组织标签体系

### 层级结构示例

```
用户：张三（zhangsan）
├── PRIVATE_zhangsan          # 私人空间（自动创建）
├── tech                      # 技术部
│   ├── backend              # 后端组
│   │   └── java             # Java小组
│   └── frontend             # 前端组
└── hr                        # 人力资源部

组织标签字段：
orgTags: "PRIVATE_zhangsan,tech,backend,java"
primaryOrg: "PRIVATE_zhangsan"
```

### 权限继承机制

```java
/**
 * 获取用户的有效组织标签（包含父标签）
 * 例如：用户属于 "tech/backend/java"
 * 有效标签：["java", "backend", "tech"]
 */
public List<String> getUserEffectiveOrgTags(String userId) {
    User user = userRepository.findById(userId).orElseThrow();
    List<String> effectiveTags = new ArrayList<>();
    
    // 用户直接所属的标签
    List<String> directTags = Arrays.asList(user.getOrgTags().split(","));
    
    for (String tag : directTags) {
        effectiveTags.add(tag);
        
        // 递归添加所有父标签
        String parentTag = orgTagCacheService.getParentTag(tag);
        while (parentTag != null) {
            effectiveTags.add(parentTag);
            parentTag = orgTagCacheService.getParentTag(parentTag);
        }
    }
    
    return effectiveTags;
}
```

### 权限验证场景

#### 场景1：文档上传
```java
// 用户上传文档时，可以选择分配给哪个组织标签
@PostMapping("/api/v1/upload")
public ResponseEntity<?> upload(@RequestParam("orgTag") String orgTag) {
    // 验证用户是否属于该组织标签
    List<String> userTags = getUserEffectiveOrgTags(currentUserId);
    if (!userTags.contains(orgTag)) {
        throw new CustomException("You don't have permission for this organization", 403);
    }
    
    // 继续上传逻辑...
}
```

#### 场景2：文档搜索
```java
// 用户搜索时，只能搜索到以下文档：
// 1. 自己上传的文档（user_id = 当前用户）
// 2. 公开的文档（is_public = true）
// 3. 所属组织的文档（org_tag in 用户的有效标签列表）

public List<SearchResult> search(String query, String userId) {
    List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
    
    // Elasticsearch 查询
    BoolQuery.Builder boolQuery = new BoolQuery.Builder()
        .should(s -> s.term(t -> t.field("user_id").value(userId)))      // 自己的文档
        .should(s -> s.term(t -> t.field("is_public").value(true)))       // 公开文档
        .should(s -> s.terms(t -> t.field("org_tag").terms(userEffectiveTags)))  // 组织文档
        .minimumShouldMatch("1");  // 至少满足一个条件
    
    return executeSearch(boolQuery);
}
```

### 管理员分配组织标签

```java
/**
 * 管理员为用户分配组织标签
 */
@Transactional
public void assignOrgTagsToUser(Long userId, List<String> orgTags, String adminUsername) {
    // 1. 验证管理员权限
    User admin = userRepository.findByUsername(adminUsername).orElseThrow();
    if (admin.getRole() != User.Role.ADMIN) {
        throw new CustomException("Only administrators can assign tags", HttpStatus.FORBIDDEN);
    }
    
    // 2. 验证所有标签是否存在
    for (String tagId : orgTags) {
        if (!organizationTagRepository.existsByTagId(tagId)) {
            throw new CustomException("Tag " + tagId + " not found", HttpStatus.NOT_FOUND);
        }
    }
    
    // 3. 获取用户
    User user = userRepository.findById(userId).orElseThrow();
    
    // 4. 保留用户的私人标签
    String privateTagId = "PRIVATE_" + user.getUsername();
    Set<String> finalTags = new HashSet<>(orgTags);
    finalTags.add(privateTagId);  // 确保私人标签不会被删除
    
    // 5. 更新用户组织标签
    user.setOrgTags(String.join(",", finalTags));
    if (user.getPrimaryOrg() == null) {
        user.setPrimaryOrg(privateTagId);  // 默认主组织为私人标签
    }
    userRepository.save(user);
    
    // 6. 清除缓存
    orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());
    orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());
}
```

---

## 权限控制

### 三层权限控制体系

```
┌────────────────────────────────────────┐
│  1. URL 级别权限（SecurityConfig）      │
│  - 基于角色的 URL 访问控制              │
│  - permitAll / hasRole("USER") / ...   │
└────────────────┬───────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────┐
│  2. 组织标签权限（OrgTagFilter）       │
│  - 验证用户是否属于资源的组织           │
│  - 数据隔离和多租户控制                 │
└────────────────┬───────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────┐
│  3. 业务逻辑权限（Service 层）         │
│  - 细粒度的资源所有权验证               │
│  - 例如：只能删除自己上传的文档         │
└────────────────────────────────────────┘
```

### 实例：删除文档的权限检查

```java
@DeleteMapping("/api/v1/documents/{fileId}")
public ResponseEntity<?> deleteDocument(@PathVariable String fileId, 
                                       @RequestHeader("Authorization") String token) {
    String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    User user = userRepository.findByUsername(username).orElseThrow();
    
    // 1. 查询文档信息
    FileUpload file = fileUploadRepository.findByFileMd5(fileId).orElseThrow();
    
    // 2. 权限验证（三种情况可以删除）
    boolean canDelete = false;
    
    // 情况1：是文档所有者
    if (file.getUserId().equals(user.getId().toString())) {
        canDelete = true;
    }
    
    // 情况2：是管理员
    if (user.getRole() == User.Role.ADMIN) {
        canDelete = true;
    }
    
    // 情况3：用户属于文档的组织，且文档不是私有的
    List<String> userTags = getUserEffectiveOrgTags(user.getId().toString());
    if (userTags.contains(file.getOrgTag()) && !file.getIsPublic()) {
        canDelete = true;
    }
    
    if (!canDelete) {
        throw new CustomException("You don't have permission to delete this document", 403);
    }
    
    // 3. 执行删除
    documentService.deleteDocument(fileId);
    
    return ResponseEntity.ok(Map.of("code", 200, "message", "Document deleted successfully"));
}
```

---

## 管理员功能

### 管理员专属接口

#### 1. 获取所有用户列表
```java
@GetMapping("/api/v1/admin/users")
public ResponseEntity<?> getAllUsers(@RequestHeader("Authorization") String token) {
    String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    validateAdmin(adminUsername);  // 验证管理员身份
    
    List<User> users = userRepository.findAll();
    
    // 移除敏感信息（密码）
    users.forEach(user -> user.setPassword(null));
    
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Get all users successful",
        "data", users
    ));
}

/**
 * 验证管理员身份
 */
private User validateAdmin(String username) {
    User admin = userRepository.findByUsername(username)
        .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
    
    if (admin.getRole() != User.Role.ADMIN) {
        throw new CustomException("Admin permission required", HttpStatus.FORBIDDEN);
    }
    
    return admin;
}
```

#### 2. 创建管理员账号
```java
@PostMapping("/api/v1/admin/create-admin")
public ResponseEntity<?> createAdminUser(@RequestHeader("Authorization") String token,
                                        @RequestBody CreateAdminRequest request) {
    String creatorUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    
    // 只有管理员才能创建管理员
    userService.createAdminUser(
        request.username(), 
        request.password(), 
        creatorUsername
    );
    
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Admin user created successfully"
    ));
}
```

#### 3. 创建组织标签
```java
@PostMapping("/api/v1/admin/org-tags")
public ResponseEntity<?> createOrgTag(@RequestHeader("Authorization") String token,
                                     @RequestBody CreateOrgTagRequest request) {
    String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    
    OrganizationTag tag = userService.createOrganizationTag(
        request.tagId(),
        request.name(),
        request.description(),
        request.parentTag(),  // 可选的父标签
        adminUsername
    );
    
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Organization tag created successfully",
        "data", tag
    ));
}
```

#### 4. 为用户分配组织标签
```java
@PostMapping("/api/v1/admin/users/{userId}/org-tags")
public ResponseEntity<?> assignOrgTags(@PathVariable Long userId,
                                      @RequestBody AssignOrgTagsRequest request,
                                      @RequestHeader("Authorization") String token) {
    String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    
    userService.assignOrgTagsToUser(
        userId,
        request.orgTags(),
        adminUsername
    );
    
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Organization tags assigned successfully"
    ));
}
```

---

## 代码实战解析

### 场景1：用户注册并登录完整流程

```bash
# 1. 用户注册
curl -X POST http://localhost:8081/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "MyPassword123"
  }'

# 响应：
{
  "code": 200,
  "message": "User registered successfully"
}

# 数据库变化：
# users 表新增：
# id=1001, username=zhangsan, password=$2a$10$..., role=USER, 
# orgTags=PRIVATE_zhangsan, primaryOrg=PRIVATE_zhangsan

# organization_tags 表新增：
# tagId=PRIVATE_zhangsan, name=zhangsan的私人空间, createdBy=1001

# Redis 缓存：
# Key: user:zhangsan:orgTags
# Value: ["PRIVATE_zhangsan"]


# 2. 用户登录
curl -X POST http://localhost:8081/api/v1/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "MyPassword123"
  }'

# 响应：
{
  "code": 200,
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}

# Token 解析后的内容：
{
  "sub": "zhangsan",
  "tokenId": "550e8400-e29b-41d4-a716-446655440000",
  "role": "USER",
  "userId": "1001",
  "orgTags": "PRIVATE_zhangsan",
  "primaryOrg": "PRIVATE_zhangsan",
  "exp": 1640995200
}

# Redis 缓存：
# Key: token:550e8400-e29b-41d4-a716-446655440000
# Value: {"userId":"1001","username":"zhangsan","expireTime":1640995200000}
# TTL: 3600秒


# 3. 获取用户信息
curl -X GET http://localhost:8081/api/v1/users/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

# 响应：
{
  "code": 200,
  "message": "Get user detail successful",
  "data": {
    "id": 1001,
    "username": "zhangsan",
    "role": "USER",
    "orgTags": ["PRIVATE_zhangsan"],
    "primaryOrg": "PRIVATE_zhangsan",
    "createdAt": "2026-01-04T10:30:00",
    "updatedAt": "2026-01-04T10:30:00"
  }
}
```

### 场景2：管理员创建组织并分配用户

```bash
# 1. 管理员登录
curl -X POST http://localhost:8081/api/v1/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "AdminPassword123"
  }'

# 响应：
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...(ADMIN_TOKEN)"
  }
}


# 2. 创建组织标签（技术部）
curl -X POST http://localhost:8081/api/v1/admin/org-tags \
  -H "Authorization: Bearer (ADMIN_TOKEN)" \
  -H "Content-Type: application/json" \
  -d '{
    "tagId": "tech",
    "name": "技术部",
    "description": "公司技术部门",
    "parentTag": null
  }'


# 3. 创建子组织标签（后端组）
curl -X POST http://localhost:8081/api/v1/admin/org-tags \
  -H "Authorization: Bearer (ADMIN_TOKEN)" \
  -H "Content-Type: application/json" \
  -d '{
    "tagId": "backend",
    "name": "后端组",
    "description": "后端开发团队",
    "parentTag": "tech"
  }'


# 4. 为用户分配组织标签
curl -X POST http://localhost:8081/api/v1/admin/users/1001/org-tags \
  -H "Authorization: Bearer (ADMIN_TOKEN)" \
  -H "Content-Type: application/json" \
  -d '{
    "orgTags": ["backend", "tech"]
  }'

# 用户 zhangsan 的组织标签变更为：
# orgTags: "PRIVATE_zhangsan,backend,tech"
# 有效标签（含父标签）: ["PRIVATE_zhangsan", "backend", "tech"]


# 5. 查询所有用户
curl -X GET http://localhost:8081/api/v1/admin/users \
  -H "Authorization: Bearer (ADMIN_TOKEN)"

# 响应：
{
  "code": 200,
  "data": [
    {
      "id": 1001,
      "username": "zhangsan",
      "role": "USER",
      "orgTags": "PRIVATE_zhangsan,backend,tech",
      "primaryOrg": "PRIVATE_zhangsan"
    },
    {
      "id": 1002,
      "username": "lisi",
      "role": "USER",
      "orgTags": "PRIVATE_lisi,frontend",
      "primaryOrg": "PRIVATE_lisi"
    }
  ]
}
```

---

## 最佳实践

### 1. 密码安全
```java
// ✅ 正确：使用 BCrypt
String encodedPassword = PasswordUtil.encode(rawPassword);

// ❌ 错误：明文存储
user.setPassword(rawPassword);

// ❌ 错误：MD5（已不安全）
String md5Password = DigestUtils.md5Hex(rawPassword);
```

### 2. Token 验证
```java
// ✅ 正确：优先使用 Redis 缓存
public boolean validateToken(String token) {
    String tokenId = extractTokenIdFromToken(token);
    // 先查 Redis，再验证 JWT 签名
    return tokenCacheService.existsToken(tokenId) && validateJwtSignature(token);
}

// ❌ 错误：每次都验证 JWT 签名和查数据库
public boolean validateToken(String token) {
    Jws<Claims> claims = Jwts.parserBuilder()
        .setSigningKey(secretKey)
        .build()
        .parseClaimsJws(token);
    String username = claims.getBody().getSubject();
    return userRepository.existsByUsername(username);
}
```

### 3. 权限验证
```java
// ✅ 正确：在 Service 层验证业务权限
@Service
public class DocumentService {
    public void deleteDocument(String fileId, String username) {
        FileUpload file = fileUploadRepository.findByFileMd5(fileId).orElseThrow();
        
        // 验证权限
        if (!file.getUserId().equals(getCurrentUserId(username)) && !isAdmin(username)) {
            throw new CustomException("Permission denied", HttpStatus.FORBIDDEN);
        }
        
        // 执行删除
        minioService.deleteFile(file.getStoragePath());
        fileUploadRepository.delete(file);
    }
}

// ❌ 错误：在 Controller 层直接操作数据
@DeleteMapping("/documents/{fileId}")
public ResponseEntity<?> deleteDocument(@PathVariable String fileId) {
    fileUploadRepository.deleteByFileMd5(fileId);  // 没有权限验证！
    return ResponseEntity.ok("Deleted");
}
```

### 4. 异常处理
```java
// ✅ 正确：统一异常处理
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> handleCustomException(CustomException e) {
        return ResponseEntity.status(e.getStatus())
            .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception e) {
        logger.error("Unexpected error", e);
        return ResponseEntity.status(500)
            .body(Map.of("code", 500, "message", "Internal server error"));
    }
}

// Service 层抛出业务异常
if (userRepository.findByUsername(username).isPresent()) {
    throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
}
```

### 5. 日志记录
```java
// ✅ 正确：使用结构化日志
logger.info("User login successful, username: {}, ip: {}", username, clientIp);
LogUtils.logUserOperation(username, "LOGIN", "authentication", "SUCCESS");

// ❌ 错误：敏感信息记录到日志
logger.info("User login: username={}, password={}", username, password);  // 危险！
```

### 6. 事务管理
```java
// ✅ 正确：使用 @Transactional
@Transactional
public void registerUser(String username, String password) {
    User user = new User();
    user.setUsername(username);
    user.setPassword(PasswordUtil.encode(password));
    userRepository.save(user);
    
    // 创建组织标签
    createPrivateOrgTag(user);
    
    // 如果这里出错，user 创建也会回滚
}

// ❌ 错误：没有事务管理
public void registerUser(String username, String password) {
    User user = new User();
    userRepository.save(user);          // 已保存
    createPrivateOrgTag(user);          // 这里失败
    // 结果：用户创建成功，但组织标签创建失败，数据不一致！
}
```

---

## 总结

### 核心知识点回顾

✅ **用户实体设计**
- User 实体：id、username、password、role、orgTags、primaryOrg
- OrganizationTag 实体：支持层级组织结构

✅ **认证流程**
1. 用户注册 → 密码 BCrypt 加密 → 创建私人组织标签
2. 用户登录 → 验证密码 → 生成 JWT Token → 缓存到 Redis

✅ **JWT Token**
- 结构：Header + Payload + Signature
- 内容：username、role、userId、orgTags、tokenId、exp
- 缓存：Redis 存储 Token 信息，提升性能

✅ **Token 刷新**
- 预刷新：剩余时间 < 5分钟时自动刷新
- 宽限期：过期后 10分钟内仍可刷新
- 响应头返回：`New-Token` header

✅ **组织标签体系**
- 私人标签：`PRIVATE_username`（每个用户专属）
- 层级结构：支持父子标签（如 tech/backend/java）
- 权限继承：属于子标签自动拥有父标签权限

✅ **权限控制**
- URL 级别：Spring Security 配置（permitAll / hasRole）
- 组织级别：OrgTagAuthorizationFilter 验证
- 业务级别：Service 层细粒度权限验证

✅ **管理员功能**
- 查看所有用户
- 创建管理员账号
- 创建和管理组织标签
- 为用户分配组织标签

### 学习建议

1. **动手实践**：按照文档中的 curl 命令测试每个接口
2. **调试代码**：在关键方法打断点，观察数据流转
3. **阅读日志**：运行项目时查看日志输出，理解流程
4. **扩展功能**：尝试添加新的权限规则或用户属性
5. **性能优化**：思考 Redis 缓存的使用场景和优化策略

### 进阶学习路径

1. **安全增强**
   - 实现验证码功能
   - 添加登录失败次数限制
   - IP 白名单/黑名单

2. **审计日志**
   - 记录所有用户操作
   - 敏感操作二次验证

3. **单点登录（SSO）**
   - 集成 OAuth2
   - 支持第三方登录（微信、GitHub）

4. **多因素认证（MFA）**
   - 手机验证码
   - Google Authenticator

---

**文档版本**: v1.0  
**最后更新**: 2026-01-04  
**作者**: AI Assistant based on PaiSmart source code

如有疑问，请参考项目源码或提交 Issue！


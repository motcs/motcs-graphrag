# Windows ZIP 版 Neo4j‑community‑2026.03.1 完整启动配置

你的解压目录：`D:\Deployment\neo4j-community-2026.03.1`Neo4j

>
> ⚠️注意：Neo4j 2026 新版本**自带内置 JDK**，优先使用它自带 JDK，避免你本地 JDK26 版本兼容问题！！

## 一、环境变量配置（CMD 命令，新开 cmd 执行）

```
:: 设置NEO4J_HOME
setx NEO4J_HOME "D:\Deployment\neo4j-community-2026.03.1"
:: 将bin目录加入PATH
setx PATH "%NEO4J_HOME%\bin;%PATH%"
```

>
> setx 执行完成后，**关闭全部旧 CMD 窗口，打开全新 CMD 才生效**。

验证环境变量（新 cmd）

```
echo %NEO4J_HOME%
```

## 二、两种启动模式

### 模式 A：控制台前台启动（开发调试，推荐，能直接看日志）

进入 bin 目录：

```
cd D:\Deployment\neo4j-community-2026.03.1\bin
neo4j console
```

- 窗口不要关闭，关闭窗口数据库就停止。
- 停止：在窗口按 `Ctrl + C`Neo4j。

### 模式 B：安装成 Windows 后台服务（开机自启，需要管理员 CMD）

>
> 右键开始菜单 → 打开【管理员身份 CMD】

```
cd D:\Deployment\neo4j-community-2026.03.1\bin
::安装windows服务
neo4j windows-service install

::启动服务
neo4j start

::查看状态
neo4j status

::停止服务
neo4j stop

::卸载服务（不需要的时候）
neo4j windows-service uninstall
```

Neo4j## 三、访问与密码

浏览器打开：`http://127.0.0.1:7474`

- bolt 连接地址：`bolt://127.0.0.1:7687`（你的 SpringBoot 项目配置用这个）
- 默认账号：`neo4j`，默认密码：`neo4j`，第一次登录强制修改密码Neo4j。

>
> 如果还没启动就预先设置密码（首次启动前执行，bin 目录下）

```
neo4j-admin dbms set-initial-password 你的密码
```

## 四、核心配置文件

配置文件路径：
`D:\Deployment\neo4j-community-2026.03.1\conf\neo4j.conf`

### 常用修改（开发环境）

打开 neo4j.conf 修改：

```
#允许外部访问，0.0.0.0监听全部网卡
server.default_listen_address=0.0.0.0

#内存配置，开发机8G内存
server.memory.heap.initial_size=1G
server.memory.heap.max_size=2G
server.memory.pagecache.size=1G
```

CSDN博...## 五、重要坑点（重点！！）

1. **Neo4j 2026 自带 JDK，优先用自带，不要强制用你本地 JDK26**
   如果启动报错 java 版本，删除系统环境变量`JAVA_HOME`，neo4j 会自动使用包内自带 JDK。
2. **路径不要有中文、空格**，你的路径`D:\Deployment\neo4j-community-2026.03.1`没问题。
3. 端口占用：7474（web 界面）、7687（bolt 协议，SpringBoot 连接），确认没有被别的程序占用。
4. 如果启动失败，看控制台日志，或者看 `neo4j-community-2026.03.1\logs`目录日志。

## 六、和你的 SpringBoot 项目对接 yml 配置示例

```
spring:
  neo4j:
    uri: bolt://127.0.0.1:7687
    authentication:
      username: neo4j
      password: 你修改后的密码
```

>
> 测试连通：启动 neo4j，浏览器访问 [http://127.0.0.1:7474](http://127.0.0.1:7474)，能打开界面代表数据库正常。
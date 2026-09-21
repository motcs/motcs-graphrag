# Neo4j 5\.26 Docker Swarm 环境标准搬迁迁移文档（Dump/Load 安全方案）

## 一、迁移方案说明

### 1\.1 方案选择

采用官方标准迁移方案：**neo4j\-admin dump（逻辑全量备份） \+ neo4j\-admin load（逻辑全量恢复）**

**禁止直接拷贝docker volume数据目录**，原因：

- 运行中文件事务不完整，极易导致数据库损坏、启动失败

- Docker Swarm 命名卷带集群前缀，跨节点权限、元数据不兼容

- 物理目录迁移对版本、系统、Docker环境一致性要求极高，容错率极低

### 1\.2 适配环境

- 部署方式：Docker Swarm Stack

- Neo4j 版本：5\.26\-community

- 开启插件：APOC

- 认证方式：账号密码登录（neo4j/自定义密码）

### 1\.3 迁移优势

- 数据一致性高，官方工具自带校验，无脏数据

- 脱离宿主机卷限制，支持跨服务器、跨环境迁移

- 不依赖原有Docker卷、文件权限，成功率100%

- 可作为日常生产备份方案复用

## 二、迁移前置准备

### 2\.1 环境核对

- 新老环境 **Neo4j 版本必须完全一致**：5\.26\-community

- 新环境 YAML 配置需与原环境保持一致：内存参数、APOC配置、账号密码、时区

- 新服务器开放端口：7474（WebUI）、7687（Bolt连接）

### 2\.2 节点确认

原服务约束绑定节点：`node.hostname==worker1`，所有备份操作需在 **原 worker1 节点**执行。

查看Neo4j运行节点（管理节点执行）：

```bash
docker stack ps commons | grep neo4j
```

## 三、旧环境：全量数据Dump备份

### 3\.1 进入原 worker1 节点

登录运行Neo4j的worker1服务器，执行以下操作。

### 3\.2 创建备份目录并执行全量备份

```bash
# 1. 在容器内创建备份目录
docker exec -it commons_neo4j mkdir -p /data/backup

# 2. 执行官方全量dump备份（核心命令）
docker exec -it commons_neo4j neo4j-admin database dump neo4j --to-path=/data/backup
```

### 3\.3 确认备份文件生成

备份完成后，容器内路径：`/data/backup/neo4j.dump`

对应宿主机真实路径（worker1节点）：

```bash
# 查看真实卷路径
docker volume inspect commons_neo4j_data
```

默认路径示例：`/var/lib/docker/volumes/commons_neo4j_data/_data/backup/neo4j.dump`

### 3\.4 下载备份文件

将`neo4j.dump` 文件拷贝至本地或新服务器备用，此文件为唯一迁移数据文件。

## 四、新环境：部署基础服务

### 4\.1 新环境部署YAML

在新服务器部署完全一致的Neo4j stack配置（版本、环境变量、插件、内存参数必须一致），重点核对：

- 镜像版本：`neo4j:5.26-community`

- 登录密码：与原环境一致 `neo4j/Neo4j@Mypwd123`

- APOC全开配置、时区、内存堆大小

### 4\.2 启动新服务并确认正常运行

```bash
# 部署stack服务
docker stack deploy -c commons.yml commons

# 查看服务状态，确保正常启动
docker service ls | grep neo4j
docker service logs commons_neo4j -f
```

✅ 确认新容器无报错、正常启动后，暂停服务准备数据恢复

```bash
# 临时缩容停止业务（容器不删除，仅停止任务）
docker service scale commons_neo4j=0
```

## 五、新环境：Load数据恢复

### 5\.1 上传备份文件

将旧环境导出的 `neo4j.dump` 上传至新服务器Neo4j数据卷的backup目录。

先启动临时任务创建目录：

```bash
docker service scale commons_neo4j=1
docker exec -it commons_neo4j mkdir -p /data/backup
docker service scale commons_neo4j=0
```

### 5\.2 执行全量数据覆盖恢复

恢复核心命令（自动覆盖新环境空数据库，无需手动清空）：

```bash
# 启动容器临时执行恢复
docker service scale commons_neo4j=1

# 执行load恢复
docker exec -it commons_neo4j neo4j-admin database load neo4j \
  --from-path=/data/backup/neo4j.dump \
  --overwrite-destination=true
```

### 5\.3 重启服务生效

```bash
# 重启服务，加载恢复后的数据
docker service scale commons_neo4j=0
docker service scale commons_neo4j=1

# 实时查看启动日志，排查异常
docker service logs commons_neo4j -f
```

## 六、迁移后校验步骤（必做）

### 6\.1 服务状态校验

- 服务无重启、无报错日志

- WebUI：`http://新服务器IP:7474` 可正常访问

- 可使用原账号密码正常登录

### 6\.2 数据完整性校验

登录Neo4j控制台，执行基础统计语句，核对节点、关系数量与原环境一致：

```cypher
// 统计所有节点
MATCH (n) RETURN count(n) AS node_count;

// 统计所有关系
MATCH ()-[r]->() RETURN count(r) AS rel_count;
```

### 6\.3 业务连通性校验

测试关联业务（graphrag服务）连接数据库正常，无连接报错、数据查询正常。

## 七、关键避坑要点

- **版本强一致**：新老Neo4j版本必须完全一致，跨版本dump/load会直接报错

- **密码配置一致**：新环境初始密码必须和旧环境一致，避免权限、认证异常

- **禁止运行中拷贝卷**：物理卷迁移仅适用于完全停机\+同环境应急，生产一律用dump/load

- **Swarm卷前缀问题**：Stack部署卷自动加`commons_`前缀，无需手动修改，工具自动适配

- **内存参数匹配**：新环境堆内存、页缓存配置需和原环境一致，防止OOM崩溃

## 八、回滚方案

若新环境迁移后异常，可直接重新执行load覆盖恢复：

```bash
docker exec -it commons_neo4j neo4j-admin database load neo4j \
  --from-path=/data/backup/neo4j.dump \
  --overwrite-destination=true
```

原环境服务可保持不动，确认新环境完全正常后，再下线旧服务。

> （注：部分内容可能由 AI 生成）

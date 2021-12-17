基于数据库的kafka认证、鉴权插件。
认证和鉴权相互独立，互不影响。
认证功能兼容kafka默认配置。

## 前提
本来生产环境的kafka是使用kerberos认证+ranger鉴权的。

但是因为kafka跨语言使用比较多,而其他语言使用kerberos存在较大难度
还是想使用原有用户/密码方式进行认证，最好拓展用户/密码数据支持从数据库中获取。

而ranger鉴权比较复杂，不一定适合所有场景。
大多数情况下，我们只关心用户对特定名称的队列的读写权限。
这一部分可进行简化。

## 认证
### 设计说明
实现效果：支持配置文件和数据库认证并存
实现类： cn.linshenkx.bigdata.kafka.auth.DbCallbackHandler
实现逻辑： 
首先使用默认的PlainLoginModule策略进行认证，
如果认证失败，且开启enable_db_auth，再使用数据库进行认证。
### 使用说明
#### 1.添加jar包
1. 工程jar包
   kafka-db-auth-1.0-all.jar
2. 数据库驱动包
   所有数据库连接参数都使用前缀匹配转换成druid连接参数，不固定

#### 2.配置
在server.properties加入以下配置
```markdown
listener.name.sasl_plaintext.plain.sasl.server.callback.handler.class=cn.linshenkx.bigdata.kafka.auth.DbCallbackHandler

```
在jaas配置文件加入以下配置
这里的配置含义是：
1. 通过文件配置3个用户：admin（123456），test01（test123456），test02（test123456）
2. `conn_` 开头的是druid连接参数，可自行添加修改
3. db_userTable为kafka用户表，表中有 name、password 字段即可（明文）。
```markdown
KafkaServer {
	org.apache.kafka.common.security.plain.PlainLoginModule required
	username="admin"
	password="123456"
	user_admin="123456"
	user_test01="test123456"
	user_test02="test123456"
    enable_db_auth="false"
	conn_url="jdbc:mysql://ip:3306/kafka_test?serverTimezone=Hongkong&useUnicode=true&characterEncoding=utf-8&useSSL=false&autoReconnect=true"
	conn_username="root"
	conn_password="123456"
	conn_driverClass="com.mysql.cj.jdbc.Driver"
	db_userTable="kafka_user"
	;
};

```
## 鉴权
### 设计说明
注意，kafka默认的鉴权体系比较复杂，这里进行了简化，并只保留了对topic、group的控制。
以下是kafka默认鉴权体系和简化版本的对比。
#### 默认版本
##### acl
默认acls的格式是
Principal P is [Allowed/Denied] Operation O From Host H On Resource R

其中操作O包括：
Read、Write、Create、Delete、Alter、Describe、ClusterAction、DescribeConfigsAlterConfigs、IdempotentWrite、All

资源R包括：
Topic、Group、Cluster、TransactionalId、DelegationToken

两两组合有数十种语义，
具体参考：
https://kafka.apache.org/documentation/#security_authz
https://docs.confluent.io/platform/current/kafka/authorization.html#overview

##### 鉴权逻辑
1. 超级用户直接通过
2. 寻找资源对应的权限配置信息，如果没找到，根据 allow.everyone.if.no.acl.found 决定是否通过（默认不通过）
3. 如果匹配到DENY类的权限，直接不通过
4. 如果匹配到ALLOW类的权限，通过

#### 简化后的版本
##### acl
acls格式是： 用户P is Allowed 操作O From * On 资源R
核心为 操作O 和 资源R
##### 鉴权逻辑
1. 超级用户直接通过
2. 资源类型为 Cluster 直接不通过
3. 资源类型为 TransactionalId、DelegationToken 直接通过
4. 寻找资源对应的权限配置信息，找到则通过，否则不通过

##### 寻找资源的逻辑
1. 在内存中维护权限配置表，定时（5s）从数据库中同步。
2. 根据请求资源名（topic、group）对内存中的权限配置表每一项的资源名进行`通配符匹配`

##### 授权举例：
###### 写
写操作授权逻辑较简单，一步到位。
1. 授予user1用户对test1的队列具有write权限

###### 读
读操作逻辑分两步，除了队列的权限，还要有消费者组的权限
1. 授予user2用户对 test开头 的队列具有read权限
2. 授予user2用户对 group_user2 的group具有 read权限（语义包含JoinGroup, LeaveGroup等）

### 使用说明
#### 1.添加jar包
1. 工程jar包
   kafka-db-auth-1.0-all.jar
2. 数据库驱动包
   所有数据库连接参数都使用前缀匹配转换成druid连接参数，不固定


#### 2.配置
在server.properties加入以下配置

- super.users
  超级用户，多个用;隔开
- authorizer.class.name
固定为cn.linshenkx.bigdata.kafka.auth.DbAclAuthorizer
- my.db.conn.* 
数据库连接配置，*内容为druid配置项
- my.acl.table 
acl表名
- my.acl.column.user_pattern 
用户字段名，字段值支持通配符表达式
- my.acl.column.resource_type 
资源类型字段名,字段值支持：topic、group
- my.acl.column.resource_pattern 
资源字段名,字段值支持通配符表达式
- my.acl.column.operation 
操作字段名，字段值支持 包括ALL在内的具体项 和 不包括ALL在内的用逗号隔开的具体项列表
- my.acl.sync.interval_second 
从数据库同步的间隔，单位为秒
如
```properties
super.users=admin
authorizer.class.name=cn.linshenkx.bigdata.kafka.auth.DbAclAuthorizer
my.db.conn.url=jdbc:mysql://ip:3306/kafka_test?serverTimezone=Hongkong&useUnicode=true&characterEncoding=utf-8&useSSL=false&autoReconnect=true
my.db.conn.username=root
my.db.conn.password=123456
my.db.conn.driverClass=com.mysql.cj.jdbc.Driver
my.acl.table=kafka_acl
my.acl.column.user_pattern=user_pattern
my.acl.column.resource_type=resource_type
my.acl.column.resource_pattern=resource_pattern
my.acl.column.operation=operation
my.acl.sync.interval_second=5
```

## 其他
### 推荐阅读
Kafka权限管理实战：http://blog.itpub.net/69940568/viewspace-2653946

### 开发
我用的是wsl环境，在gradle.properties配置了代理，如果不需要请删掉
打包命令：gradle clean shadowJar
### kafka单机服务器测试命令
```shell
# 启动zookeeper
unset KAFKA_DEBUG
nohup $KAFKA_HOME/bin/zookeeper-server-start.sh $KAFKA_HOME/config/zookeeper.properties  1>>$KAFKA_HOME/logs/zookeeper.out 2>&1   &

# 启动kafka
export KAFKA_DEBUG=true # 有值即可
export KAFKA_OPTS="-Djava.security.auth.login.config=$KAFKA_HOME/config/kafka_jaas.conf"
nohup $KAFKA_HOME/bin/kafka-server-start.sh $KAFKA_HOME/config/server.properties  1>>$KAFKA_HOME/logs/kafka.out 2>&1   &

# 跟踪日志
tail -n 1000 -f $KAFKA_HOME/logs/server.log

# 停止zookeeper
$KAFKA_HOME/bin/zookeeper-server-stop.sh $KAFKA_HOME/config/zookeeper.properties

# 停止kafka
$KAFKA_HOME/bin/kafka-server-stop.sh $KAFKA_HOME/config/server.properties
# 清空日志
rm -f $KAFKA_HOME/logs/*

```
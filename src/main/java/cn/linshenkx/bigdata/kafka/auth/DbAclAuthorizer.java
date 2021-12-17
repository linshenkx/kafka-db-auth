package cn.linshenkx.bigdata.kafka.auth;


import cn.linshenkx.bigdata.kafka.auth.util.PatternMatchUtils;
import com.alibaba.druid.pool.DruidDataSource;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.BeanListHandler;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * db acl鉴权类
 * 配置方法：在server.properties添加如下配置：
 * super.users 超级用户，多个用;隔开
 * authorizer.class.name=cn.linshenkx.bigdata.kafka.auth.DbAclAuthorizer
 * my.db.conn.* 数据库连接配置，*内容为druid配置项
 * my.acl.table acl表名
 * my.acl.column.user_pattern 用户字段名，字段值支持通配符表达式
 * my.acl.column.resource_type 资源类型字段名,字段值支持：topic、group
 * my.acl.column.resource_pattern 资源字段名,字段值支持通配符表达式
 * my.acl.column.operation 操作字段名，字段值支持 包括ALL在内的具体项 和 不包括ALL在内的用逗号隔开的具体项列表
 * my.acl.sync.interval_second 从数据库同步的间隔，单位为秒
 *
 */
public class DbAclAuthorizer implements Authorizer {

    private static final String SUPER_USERS_PROP = "super.users";
    private DruidDataSource dataSource = null;
    private final ScheduledExecutorService executorService =  Executors.newScheduledThreadPool(3);

    private Set<String> superUserSet;
    private final AtomicReference<List<DbSimpleAcl>> aclCache= new AtomicReference<>();

    private static final Logger log = LoggerFactory.getLogger(DbAclAuthorizer.class.getName());

    @Override
    public Map<Endpoint, ? extends CompletionStage<Void>> start(AuthorizerServerInfo serverInfo) {
        log.info("start");
        return new HashMap<>();
    }

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        return actions.stream().map(action -> authorizeAction(requestContext, action)).collect(Collectors.toList());
    }

    private AuthorizationResult authorizeAction(AuthorizableRequestContext requestContext, Action action) {
        ResourcePattern resource = action.resourcePattern();
        if (resource.patternType() != PatternType.LITERAL) {
            throw new IllegalArgumentException("Only literal resources are supported. Got: " + resource.patternType());
        }
        String principal = requestContext.principal().getName();

        AclOperation operation = action.operation();
        //1 超级用户直接通过
        if(superUserSet.contains(principal)){
            return AuthorizationResult.ALLOWED;
        }
        //2 资源类型为 Cluster 直接不通过
        if(resource.resourceType().equals(ResourceType.CLUSTER)){
            return AuthorizationResult.DENIED;
        }
        //3 资源类型为 TransactionalId、DelegationToken 直接通过
        if(resource.resourceType().equals(ResourceType.TRANSACTIONAL_ID)||resource.resourceType().equals(ResourceType.DELEGATION_TOKEN)){
            return AuthorizationResult.ALLOWED;
        }
        //4 寻找资源对应的权限配置信息，找到则通过，否则不通过
        if(matchingAcls(resource.resourceType(),resource.name(),operation,principal)){
            return AuthorizationResult.ALLOWED;
        }
        return AuthorizationResult.DENIED;
    }

    /**
     * @param resourceType
     * @param resourceName
     * @param operation
     * @param principal
     * @return
     */
    private boolean matchingAcls(ResourceType resourceType, String resourceName, AclOperation operation, String principal) {
        return aclCache.get().stream().anyMatch(acl->{
            if(resourceType.equals(ResourceType.fromString(acl.getResourceType()))
                    && PatternMatchUtils.simpleMatch(acl.getResourcePattern(),resourceName)
                    && PatternMatchUtils.simpleMatch(acl.getUserPattern(),principal)
            ){
                if(acl.getOperation().equalsIgnoreCase(AclOperation.ALL.name())){
                    return true;
                }
                if(operation.equals(AclOperation.DESCRIBE)){
                    if(Arrays.stream(acl.getOperation().split(","))
                            .map(AclOperation::fromString)
                            .anyMatch(op->op.equals(AclOperation.READ)
                                    ||op.equals(AclOperation.WRITE)
                                    ||op.equals(AclOperation.DELETE)
                                    ||op.equals(AclOperation.ALTER)
                            )
                    ){
                        return true;
                    }
                }
                return Arrays.stream(acl.getOperation().split(","))
                                .map(AclOperation::fromString)
                        .anyMatch(op->op.equals(operation));

            }
            return false;
        });
    }

    @Override
    public List<? extends CompletionStage<AclCreateResult>> createAcls(AuthorizableRequestContext requestContext, List<AclBinding> aclBindings) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<? extends CompletionStage<AclDeleteResult>> deleteAcls(AuthorizableRequestContext requestContext, List<AclBindingFilter> aclBindingFilters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<AclBinding> acls(AclBindingFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        log.info("close");
        if(dataSource!=null){
            dataSource.close();
        }
        executorService.shutdownNow();
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("configure");
        String superUsers= (String)configs.get(SUPER_USERS_PROP);
        log.info("superUsers:{}",superUsers);
        if(superUsers==null||superUsers.isEmpty()){
            superUserSet= new HashSet<>();
        }else {
            superUserSet=Arrays.stream(superUsers.split(";")).map(String::trim).collect(Collectors.toSet());
        }
        this.dataSource = new DruidDataSource();
        Properties properties=new Properties();
        for (Map.Entry<String, ?> entry : configs.entrySet()) {
            if(entry.getKey().startsWith("my.db.conn.")){
                String key=entry.getKey().replace("my.db.conn.","druid.");
                String value=(String)entry.getValue();
                log.info("datasource connection config: {}:{}",key,value);
                properties.setProperty(key,value);
            }
        }
        dataSource.configFromPropety(properties);
        String tableName = (String)configs.get("my.acl.table");
        String userPatternColumnName = (String)configs.get("my.acl.column.user_pattern");
        String resourceTypeColumnName = (String)configs.get("my.acl.column.resource_type");
        String resourcePatternColumnName = (String)configs.get("my.acl.column.resource_pattern");
        String operationColumnName = (String)configs.get("my.acl.column.operation");
        if(tableName==null||userPatternColumnName==null||resourceTypeColumnName==null||
                resourcePatternColumnName==null||operationColumnName==null){
            log.error("my.acl must be configured! ~lin");
            throw new RuntimeException("my.acl must be configured! ~lin");
        }
        String sql=String.format("select %s as userPattern, %s as resourceType,%s as resourcePattern,%s as operation from %s",
                userPatternColumnName,resourceTypeColumnName,resourcePatternColumnName,operationColumnName,
                tableName
        );
        long syncInterval=Long.parseLong((String) configs.get("my.acl.sync.interval_second"));
        executorService.scheduleWithFixedDelay(() -> {
            log.debug("sync from db");
            try(Connection conn=dataSource.getConnection()
            ) {
                QueryRunner queryRunner = new QueryRunner();
                List<DbSimpleAcl> aclList = queryRunner.query(conn, sql, new BeanListHandler<>(DbSimpleAcl.class));
                aclCache.set(aclList);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        },0,syncInterval, TimeUnit.SECONDS);
    }
}

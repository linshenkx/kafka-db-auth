package cn.linshenkx.bigdata.kafka.auth;

import com.alibaba.druid.pool.DruidDataSource;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.plain.PlainAuthenticateCallback;
import org.apache.kafka.common.security.plain.PlainLoginModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DbCallbackHandler implements AuthenticateCallbackHandler {
    private static final Logger log = LoggerFactory.getLogger(DbCallbackHandler.class.getName());
    private static final String JAAS_USER_PREFIX = "user_";
    private Map<String, ?> options;
    private DruidDataSource dataSource = null;
    List<AppConfigurationEntry> jaasConfigEntries;
    private boolean enableDbAuth;

    /**
     * 初始化配置
     *
     * @param configs
     * @param saslMechanism
     * @param jaasConfigEntries
     */
    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        log.info("configure");
        this.jaasConfigEntries = jaasConfigEntries;
        String loginModuleName = PlainLoginModule.class.getName();
        for (AppConfigurationEntry entry : jaasConfigEntries) {
            if (!loginModuleName.equals(entry.getLoginModuleName())) {
                continue;
            }
            options = entry.getOptions();
        }
        if (options == null) {
            throw new RuntimeException(PlainLoginModule.class.getName() + " must be configured! ~lin");
        }
        enableDbAuth = options.get("enable_db_auth").toString().equalsIgnoreCase("true");
        if (enableDbAuth) {
            this.dataSource = new DruidDataSource();
            Properties properties = new Properties();
            for (Map.Entry<String, ?> entry : options.entrySet()) {
                if (entry.getKey().startsWith("conn_")) {
                    String key = entry.getKey().replace("conn_", "druid.");
                    String value = (String) entry.getValue();
                    log.info("datasource connection config: {}:{}", key, value);
                    properties.setProperty(key, value);
                }
            }
            dataSource.configFromPropety(properties);
        }
    }

    /**
     * 关闭打开的相关资源
     */
    @Override
    public void close() {
        log.info("close");
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * 处理用户的认证请求
     *
     * @param callbacks
     * @throws IOException
     * @throws UnsupportedCallbackException
     */
    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        log.debug("handle");
        String username = null;
        for (Callback callback : callbacks) {
            if (callback instanceof NameCallback) {
                username = ((NameCallback) callback).getDefaultName();
            } else if (callback instanceof PlainAuthenticateCallback) {
                PlainAuthenticateCallback plainCallback = (PlainAuthenticateCallback) callback;
                boolean authenticated = authenticate(username, plainCallback.password());
                log.debug("authenticate username:{},result:{}", username, authenticated);
                plainCallback.authenticated(authenticated);
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }


    /**
     * 先用文件
     *
     * @param username
     * @param password
     * @return
     * @throws IOException
     */
    protected boolean authenticate(String username, char[] password) throws IOException {
        boolean fileAuthenticateResult = fileAuthenticate(username, password);
        log.debug("fileAuthenticateResult username:{},result:{}", username, fileAuthenticateResult);
        if (fileAuthenticateResult) {
            return true;
        }
        if (!enableDbAuth) {
            return false;
        }
        try {
            boolean dbAuthenticateResult = dbAuthenticate(username, password);
            log.debug("dbAuthenticateResult username:{},result:{}", username, dbAuthenticateResult);
            return dbAuthenticateResult;
        } catch (Exception e) {
            log.error(" dbAuthenticate failed! ~lin");
            return false;
        }

    }

    protected boolean fileAuthenticate(String username, char[] password) throws IOException {
        if (username == null) {
            return false;
        } else {
            String expectedPassword = JaasContext.configEntryOption(jaasConfigEntries,
                    JAAS_USER_PREFIX + username,
                    PlainLoginModule.class.getName());
            return expectedPassword != null && Arrays.equals(password, expectedPassword.toCharArray());
        }
    }

    private boolean dbAuthenticate(String username, char[] passwordCharArray) {
        log.debug("begin dbAuthenticateResult username:{}", username);
        String password = new String(passwordCharArray);
        String tableName = (String) options.get("db_userTable");
        if (tableName == null) {
            log.error(" db_userTable must be configured! ~lin");
            throw new RuntimeException(PlainLoginModule.class.getName() + " db_userTable must be configured! ~lin");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement("select * from " + tableName + " where name=?")
        ) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String dbPassword = resultSet.getString("password");
                    boolean checkResult = password.equals(dbPassword);
                    log.debug("user {} authentication status: {}.", username, checkResult);
                    return checkResult;
                } else {
                    log.debug("user {} not found in db.", username);
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}

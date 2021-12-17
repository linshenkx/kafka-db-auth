package cn.linshenkx.bigdata.kafka.auth;

public class DbSimpleAcl {
    private String userPattern;
    private String resourceType;
    private String resourcePattern;
    private String operation;

    public String getUserPattern() {
        return userPattern;
    }

    public void setUserPattern(String userPattern) {
        this.userPattern = userPattern;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourcePattern() {
        return resourcePattern;
    }

    public void setResourcePattern(String resourcePattern) {
        this.resourcePattern = resourcePattern;
    }


    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }
}

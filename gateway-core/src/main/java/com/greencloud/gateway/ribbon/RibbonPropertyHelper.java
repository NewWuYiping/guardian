package com.greencloud.gateway.ribbon;

import com.netflix.config.DynamicPropertyFactory;

/**
 * @program: gateway-parent
 * @description:
 * @author: WuYiping
 * @create: 2019-03-08 15:53
 **/
public class RibbonPropertyHelper {


    public static int getRibbonMaxHttpConnectionsPerHost(String serviceName) {
        int i = DynamicPropertyFactory.getInstance()
                .getIntProperty("ribbon." + serviceName + ".hystrix.maxconnections.perhost", 0)
                .get();
        if (i == 0) {
            i = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbon.hystrix.maxconnections.perhost.global", 500)
                    .get();
        }
        return i;
    }

    public static int getRibbonMaxTotalHttpConnections(String serviceName) {
        int i = DynamicPropertyFactory.getInstance()
                .getIntProperty("ribbon." + serviceName + ".hystrix.maxconnections", 0)
                .get();
        if (i == 0) {
            i = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbon.hystrix.maxconnections.global", 2000)
                    .get();
        }
        return i;
    }

    public static int getRibbonMaxAutoRetries(String serviceName) {
        int i = DynamicPropertyFactory.getInstance()
                .getIntProperty("ribbon." + serviceName + ".hystrix.maxautoretries", 0)
                .get();
        if (i == 0) {
            i = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbon.global.hystrix.maxautoretries.global", 1)
                    .get();
        }
        return i;
    }

    public static int getRibbonMaxAutoRetriesNextServer(String serviceName) {
        int i = DynamicPropertyFactory.getInstance()
                .getIntProperty(serviceName + ".ribbon.hystrix.maxautoretries.nextserver", 0)
                .get();
        if (i == 0) {
            i = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbon.hystrix.maxautoretries.nextserver.global", 1)
                    .get();
        }
        return i;
    }

    public static String getRibbonLoadBalanceRule(String groupName, String routeName) {
        String rule = DynamicPropertyFactory.getInstance()
                .getStringProperty("ribbon." + routeName + ".lb.rule", null)
                .get();
        if (rule == null) {
            rule = DynamicPropertyFactory.getInstance()
                    .getStringProperty("ribbon." + groupName + ".lb.rule", null)
                    .get();
        }
        if (rule == null) {
            rule = DynamicPropertyFactory.getInstance()
                    .getStringProperty("ribbon.lb.rule.global", "com.netflix.loadbalancer.RetryRule")
                    .get();
        }
        return rule;
    }

    public static int getRibbonConnectTimeout(String groupName, String routeName) {
        int connectTimeout = DynamicPropertyFactory.getInstance()
                .getIntProperty("ribbon." + routeName + ".connect.timeout", 0)
                .get();
        if (connectTimeout == 0) {
            connectTimeout = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbon." + groupName + ".connect.timeout", 0)
                    .get();
        }
        if (connectTimeout == 0) {
            connectTimeout = DynamicPropertyFactory.getInstance()
                    .getIntProperty("zuul.connect.timeout.global", 20000)
                    .get();
        }
        return connectTimeout;
    }

    public static int getRibbonReadTimeout(String groupName, String routeName) {
        int socketTimeout = DynamicPropertyFactory.getInstance()
                .getIntProperty("ribbbon." + routeName + ".socket.timeout", 0)
                .get();
        if (socketTimeout == 0) {
            socketTimeout = DynamicPropertyFactory.getInstance()
                    .getIntProperty("ribbbon." + groupName + ".socket.timeout", 0)
                    .get();
        }
        if (socketTimeout == 0) {
            socketTimeout = DynamicPropertyFactory.getInstance()
                    .getIntProperty("zuul.socket.timeout.global", 10000)
                    .get();
        }
        return socketTimeout;
    }

    public static boolean getRibbonRequestSpecificRetryOn(String groupName, String routeName) {
        boolean nextTry = DynamicPropertyFactory.getInstance()
                .getBooleanProperty("ribbbon." + routeName + ".next.try", false)
                .get();
        if (!nextTry) {
            nextTry = DynamicPropertyFactory.getInstance()
                    .getBooleanProperty("ribbbon." + groupName + ".next.try", false)
                    .get();
        }
        if (!nextTry) {
            nextTry = DynamicPropertyFactory.getInstance()
                    .getBooleanProperty("zuul.socket.next.try.global", true)
                    .get();
        }
        return nextTry;
    }

    public static boolean getRibbonOkToRetryOnAllOperations(String groupName, String routeName) {
        boolean sameTry = DynamicPropertyFactory.getInstance()
                .getBooleanProperty("ribbbon." + routeName + ".same.try", false)
                .get();
        if (!sameTry) {
            sameTry = DynamicPropertyFactory.getInstance()
                    .getBooleanProperty("ribbbon." + groupName + ".same.try", false)
                    .get();
        }
        if (!sameTry) {
            sameTry = DynamicPropertyFactory.getInstance()
                    .getBooleanProperty("zuul.socket.same.try.global", true)
                    .get();
        }
        return sameTry;
    }
}

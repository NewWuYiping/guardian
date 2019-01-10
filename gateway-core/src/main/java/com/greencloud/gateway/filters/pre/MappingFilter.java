package com.greencloud.gateway.filters.pre;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.greencloud.gateway.GatewayFilter;
import com.greencloud.gateway.common.ant.AntPathMatcher;
import com.greencloud.gateway.common.ant.PathMatcher;
import com.greencloud.gateway.constants.GatewayConstants;
import com.greencloud.gateway.context.RequestContext;
import com.greencloud.gateway.exception.GatewayException;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author leejianhao
 */
public class MappingFilter extends GatewayFilter {

    private static Logger logger = LoggerFactory.getLogger(MappingFilter.class);

    /**
     * /s/** = [http://api.ihotel.cn,http://api.ihotel.cn]
     */
    private static final AtomicReference<Map<String, List<String>>> serverRef = new AtomicReference<>(Maps.newHashMap());
    /**
     * /s/** = [stripPrefix=false,config=configValue]
     */
    private static final AtomicReference<Map<String, Map<String, String>>> serverConfigRef = new AtomicReference<>(Maps.newHashMap());

    /**
     * /s/weather = [http://api.ihotel.cn,http://api.ihotel.cn]
     */
    private static final AtomicReference<Map<String, List<String>>> routesTableRef = new AtomicReference<>(Maps.newHashMap());

    /**
     * /s/weather=/s/**
     */
    private static final AtomicReference<Map<String, String>> matchers = new AtomicReference<>(Maps.newHashMap());

    private static final DynamicStringProperty ROUTES_TABLE = DynamicPropertyFactory.getInstance()
            .getStringProperty(GatewayConstants.GATEWAY_ROUTES_TABLE, null);

    private static final PathMatcher matcher;

    static {
        matcher = new AntPathMatcher();

        buildRoutesTable();

        ROUTES_TABLE.addCallback(new Runnable() {
            @Override
            public void run() {
                buildRoutesTable();
            }
        });
    }

    private static void buildRoutesTable() {
        logger.info("building routes table");

        final String routesTableString = ROUTES_TABLE.get();
        if (Strings.isNullOrEmpty(routesTableString)) {
            logger.info("routes table string is empty, nothing to build");
            return;
        }

        Map<String, List<String>> serverMap = Maps.newHashMap();
        Map<String, Map<String, String>> serverConfigMap = Maps.newHashMap();

        String[] routes = routesTableString.split("\n");
        for (String route : routes) {
            if (Strings.isNullOrEmpty(route)) {
                continue;
            }
            String[] parts = StringUtils.split(route, ";");
            if (parts.length != 2) {
                continue;
            }

            String routeMappingString = parts[0];
            if (Strings.isNullOrEmpty(routeMappingString)) {
                continue;
            }

            String[] routeMappings = routeMappingString.split("=");
            if (routeMappings.length != 2) {
                continue;
            }

            String mappingAnt = routeMappings[0];
            String serverString = routeMappings[1];
            if (Strings.isNullOrEmpty(mappingAnt) || Strings.isNullOrEmpty(serverString)) {
                continue;
            }
            String[] servers = serverString.split("|");
            serverMap.put(mappingAnt, Arrays.asList(servers));

            String routesConfigString = parts[1];
            if (Strings.isNullOrEmpty(routesConfigString)) {
                continue;
            }
            String[] routesConfigs = routesConfigString.split("&");
            for (String routeConfig : routesConfigs) {
                String[] serverConfigItem = routeConfig.split("=");
                if (serverConfigItem.length != 2) {
                    continue;
                }
                Map<String, String> configs = serverConfigMap.get(mappingAnt);
                if (configs == null) {
                    configs = Maps.newHashMap();
                    serverConfigMap.put(mappingAnt, configs);
                }
                configs.put(serverConfigItem[0], serverConfigItem[1]);
            }
        }
        serverRef.set(serverMap);
        serverConfigRef.set(serverConfigMap);

        routesTableRef.get().clear();
        matchers.get().clear();
    }

    @Override
    public String filterType() {
        return "pre";
    }

    @Override
    public int filterOrder() {
        return 20;
    }

    @Override
    public boolean shouldFilter() {
        return true;
    }

    @Override
    public Object run() throws GatewayException {
        RequestContext context = RequestContext.getCurrentContext();
        HttpServletRequest request = context.getRequest();

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = StringUtils.removeStart(uri, contextPath);

        List<String> servers = findServers(path);

        int index = (int) (Math.random() * servers.size());
        String server = servers.get(index);

        String mappingAnt = matchers.get().get(path);

        boolean stripPrefix = Boolean.valueOf(serverConfigRef.get().get(mappingAnt).get("stripPrefix"));

        if (stripPrefix) {
            String prefix = mappingAnt.substring(0, mappingAnt.indexOf("/*"));
            path = StringUtils.removeStart(path, prefix);
        }

        String routeUrl = server + path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        RequestContext.getCurrentContext().setRouteUrl(routeUrl);

        return null;
    }

    private List<String> findServers(String path) throws GatewayException {
        List<String> servers = routesTableRef.get().get(path);

        if (servers != null && servers.size() > 0) {
            return servers;
        }

        boolean match = false;
        Map<String, List<String>> serverMap = serverRef.get();
        Set<String> mappingAnts = serverMap.keySet();
        for (String mappingAnt : mappingAnts) {
            if (matcher.match(mappingAnt, path)) {
                match = true;
                routesTableRef.get().put(path, serverRef.get().get(mappingAnt));
                matchers.get().put(path, mappingAnt);
                break;
            }
        }

        if (!match) {
            throw new GatewayException("API Not Found", 400, "API Not Found");
        }

        return routesTableRef.get().get(path);
    }

}
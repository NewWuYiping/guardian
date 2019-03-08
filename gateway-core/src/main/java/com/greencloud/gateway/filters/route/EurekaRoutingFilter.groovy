package com.greencloud.gateway.filters.route

import com.greencloud.gateway.GatewayFilter
import com.greencloud.gateway.constants.Constants
import com.greencloud.gateway.constants.GatewayConstants
import com.greencloud.gateway.context.Debug
import com.greencloud.gateway.context.RequestContext
import com.greencloud.gateway.exception.GatewayException
import com.greencloud.gateway.ribbon.RestClient
import com.greencloud.gateway.ribbon.RestClientFactory
import com.greencloud.gateway.ribbon.RibbonPropertyHelper
import com.greencloud.gateway.util.HTTPRequestUtil
import com.netflix.client.AbstractLoadBalancerAwareClient
import com.netflix.client.ClientException
import com.netflix.client.config.CommonClientConfigKey
import com.netflix.client.config.DefaultClientConfigImpl
import com.netflix.client.config.IClientConfig
import com.netflix.client.http.HttpRequest
import com.netflix.client.http.HttpResponse
import com.netflix.config.DynamicPropertyFactory
import com.netflix.config.DynamicStringProperty
import org.apache.http.Header
import org.apache.http.HttpHeaders
import org.apache.http.message.BasicHeader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletRequest
import java.util.zip.GZIPInputStream

class EurekaRoutingFilter extends GatewayFilter {

    private static Logger logger = LoggerFactory.getLogger(EurekaRoutingFilter.class);

    private static final DynamicStringProperty clientRefresher = DynamicPropertyFactory.getInstance()
            .getStringProperty("ribbon.refresh.serviceclient", "");


    static {
        clientRefresher.addCallback(new Runnable() {
            public void run() {
                RestClientFactory.closeRestClient(clientRefresher.get().trim());
            }
        });
    }

    @Override
    public String filterType() {
        return "route";
    }

    public int filterOrder() {
        return 22;
    }

    @Override
    public boolean shouldFilter() {
        RequestContext ctx = RequestContext.getCurrentContext();
        if (null == ctx.get(GatewayConstants.ROUTE_REGISTER_CENTER_SOURCE)) {
            return false;
        }
//        return ctx.getRoute() != null && ctx.sendZuulResponse();
        return true;
    }

    @Override
    public Object run() throws GatewayException {

        RequestContext ctx = RequestContext.getCurrentContext();

        HttpServletRequest request = ctx.getRequest();
        String uri = ctx.getRouteUrl().toString()
//		Transaction tran = Cat.getProducer().newTransaction("ExecuteRibbonFilter", uri);
        String serviceName = ctx.get(GatewayConstants.ROUTE_REGISTER_CENTER_SERVICE_NAME);
        String groupName = "default-group";
        String routeName = "default-name";

        try {

            int contentLength = request.getContentLength();
            String verb = request.getMethod().toUpperCase();
            Collection<Header> headers = buildZuulRequestHeaders(RequestContext.getCurrentContext().getRequest());
            InputStream requestEntity = getRequestBody(RequestContext.getCurrentContext().getRequest());
            IClientConfig clientConfig = buildRequestConfig(serviceName, routeName, groupName);

            RestClient client = RestClientFactory.getRestClient(serviceName, clientConfig);

//			Cat.logEvent("route.serviceName", serviceName);
            HttpResponse response = forward(client, clientConfig, verb, uri, serviceName, headers, requestEntity, contentLength,
                    groupName, routeName);
            setResponse(response);
//			tran.setStatus(Transaction.SUCCESS);
        } catch (Exception e) {
//			tran.setStatus(e);
            Exception ex = e;
            String errorMsg = "[${ex.class.simpleName}]{${ex.message}}   ";
            Throwable cause = null;
            while ((cause = ex.getCause()) != null) {
                ex = (Exception) cause;
                errorMsg = "${errorMsg}[${ex.class.simpleName}]{${ex.message}}   ";
            }

            logger.error("Service Execution Error,serviceName: ${serviceName}\nCause: ${errorMsg}\nUri:${uri}\nrouteName:${routeName}\ngroupName:${groupName}", e);
//			Cat.logError("Service Execution Error,serviceName: ${serviceName}\nCause: ${errorMsg}\nUri:${uri}\nrouteName:${routeName}\ngroupName:${groupName}", e);
            throw new GatewayException(errorMsg, 500, ",serviceName: ${serviceName}\nCause: ${errorMsg}\nUri:${uri}");
        } finally {
//			tran.complete();
        }
        return null;
    }

    public HttpResponse forward(AbstractLoadBalancerAwareClient client, IClientConfig clientConfig, String verb, String uri, String serviceName,
                                Collection<Header> headers, InputStream entity, int contentLength, String groupName, String routeName)
            throws IOException, URISyntaxException {
        entity = debug(verb, serviceName, headers, entity, contentLength);
        HttpRequest request;
        HttpRequest.Builder builder = HttpRequest.newBuilder();

        for (Header header : headers) {
            if (Constants.CAT_PARENT_MESSAGE_ID.equalsIgnoreCase(header.getName())
                    || Constants.CAT_CHILD_MESSAGE_ID.equalsIgnoreCase(header.getName())
                    || Constants.CAT_ROOT_MESSAGE_ID.equalsIgnoreCase(header.getName()))
                continue;

            builder.header(header.getName(), header.getValue());
        }
//        Cat.Context ctx = new CatContext();
//		Cat.logRemoteCallClient(ctx);
//		builder.header(Constants.CAT_ROOT_MESSAGE_ID, ctx.getProperty(Cat.Context.ROOT));
//		builder.header(Constants.CAT_PARENT_MESSAGE_ID, ctx.getProperty(Cat.Context.PARENT));
//		builder.header(Constants.CAT_CHILD_MESSAGE_ID, ctx.getProperty(Cat.Context.CHILD));

        switch (verb) {
            case "POST":
                builder.verb(HttpRequest.Verb.POST);
                request = builder.entity(entity).overrideConfig(clientConfig).uri(new URI(uri)).build();
                break;
            case "PUT":
                builder.verb(HttpRequest.Verb.PUT);
                request = builder.entity(entity).overrideConfig(clientConfig).uri(new URI(uri)).build();
                break;
            default:
                builder.verb(getVerb(verb));
                request = builder.entity(entity).overrideConfig(clientConfig).uri(new URI(uri)).build();
        }

        long start = System.currentTimeMillis();

        HttpResponse response = (HttpResponse) client.executeWithLoadBalancer(request);
        RequestContext.getCurrentContext().set("remoteCallCost", System.currentTimeMillis() - start);
        return response;
    }

    void setResponse(HttpResponse response) throws ClientException, IOException {
        RequestContext ctx = RequestContext.getCurrentContext();
        ctx.setResponseStatusCode(response.getStatus());

        boolean isOriginResponseGZipped = false;
        String headerValue;
        Map<String, Collection<String>> map = response.getHeaders();
        for (String headerName : map.keySet()) {

            headerValue = (map.get(headerName).toArray()[0]).toString();
            ctx.addOriginResponseHeader(headerName, headerValue);
            if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                ctx.setOriginContentLength(headerValue);
            }
            if (isValidZuulResponseHeader(headerName)) {
                ctx.addGatewayResponseHeader(headerName, headerValue);
            }
            if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_ENCODING)) {
                if (HTTPRequestUtil.isGzipped(headerValue)) {
                    isOriginResponseGZipped = true;
                }
            }
            if (Debug.debugRequest()) {
                Debug.addRequestDebug("ORIGIN_RESPONSE:: < ${headerName}, ${headerValue}");
            }
        }


        ctx.setResponseGZipped(isOriginResponseGZipped);

        InputStream inputStream = response.getInputStream();
        if (Debug.debugRequest()) {
            if (inputStream == null) {
                Debug.addRequestDebug("ORIGIN_RESPONSE:: < null ");
            } else {
                byte[] origBytes = inputStream.bytes;
                byte[] contentBytes = origBytes;
                if (isOriginResponseGZipped) {
                    contentBytes = new GZIPInputStream(new ByteArrayInputStream(origBytes)).bytes
                }
                String entity = new String(contentBytes);
                Debug.addRequestDebug("ORIGIN_RESPONSE:: < ${entity}");

                inputStream = new ByteArrayInputStream(origBytes);
            }
        }
        ctx.setResponseDataStream(inputStream);
    }

    public InputStream debug(String verb, String url, Collection<Header> headers,
                             InputStream requestEntity, int contentLength) throws IOException {
        if (Debug.debugRequest()) {
            RequestContext.getCurrentContext().addGatewayResponseHeader("x-target-url", url);
            Debug.addRequestDebug("ZUUL:: url=${url}");
            headers.each {
                Debug.addRequestDebug("ZUUL::> ${it.name}  ${it.value}")
            }
            if (requestEntity != null) {
                requestEntity = debugRequestEntity(requestEntity);
            }
        }
        return requestEntity;
    }

    boolean isValidZuulResponseHeader(String name) {
        switch (name.toLowerCase()) {
            case "connection":
            case "content-length":
            case "content-encoding":
            case "server":
            case "transfer-encoding":
            case "access-control-allow-origin":
            case "access-control-allow-headers":
                return false;
            default:
                return true;
        }
    }

    InputStream debugRequestEntity(InputStream inputStream) throws IOException {
        if (Debug.debugRequestHeadersOnly()) return inputStream
        if (inputStream == null) return null
        byte[] entityBytes = inputStream.getBytes();
        String entity = new String(entityBytes);
        Debug.addRequestDebug("ZUUL::> ${entity}")
        return new ByteArrayInputStream(entityBytes);
    }


    private Collection<Header> buildZuulRequestHeaders(HttpServletRequest request) {
        Map<String, Header> headersMap = new HashMap<>();

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = ((String) headerNames.nextElement()).toLowerCase();
            String value = request.getHeader(name);
            if (isValidZuulRequestHeader(name)) {
                headersMap.put(name, new BasicHeader(name, value));
            }
        }

        Map<String, String> zuulRequestHeaders = RequestContext.getCurrentContext().getGatewayRequestHeaders();
        for (String key : zuulRequestHeaders.keySet()) {
            String name = key.toLowerCase();
            String value = zuulRequestHeaders.get(key);
            headersMap.put(name, new BasicHeader(name, value));
        }

        if (RequestContext.getCurrentContext().getResponseGZipped()) {
            String name = "accept-encoding";
            String value = "gzip";
            headersMap.put(name, new BasicHeader(name, value));
        }
        return headersMap.values();
    }

    public boolean isValidZuulRequestHeader(String name) {
        if (name.toLowerCase().contains("content-length")) {
            return false;
        }
        if (!RequestContext.getCurrentContext().getResponseGZipped()) {
            if (name.toLowerCase().contains("accept-encoding")) {
                return false;
            }
        }
        return true;
    }

    private InputStream getRequestBody(HttpServletRequest request) {
        InputStream requestEntity = null;
        try {
            requestEntity = request.getInputStream();
        } catch (IOException e) {
            // no requestBody is ok.
        }
        return requestEntity;
    }

    private IClientConfig buildRequestConfig(String serviceName, String routeName, String routeGroup) {
        DefaultClientConfigImpl clientConfig = new DefaultClientConfigImpl();
        clientConfig.loadProperties(serviceName);
        clientConfig.setProperty(CommonClientConfigKey.NIWSServerListClassName, "com.greencloud.gateway.ribbon.DiscoveryServerList");
        clientConfig.setProperty(CommonClientConfigKey.ClientClassName, "com.greencloud.gateway.ribbon.RestClient");
//        clientConfig.setProperty(CommonClientConfigKey.NIWSServerListClassName, "com.netflix.niws.loadbalancer.DiscoveryEnabledNIWSServerList");
//        clientConfig.setProperty(CommonClientConfigKey.NFLoadBalancerRuleClassName, RibbonPropertyHelper.getRibbonLoadBalanceRule(routeGroup, routeName));
        clientConfig.setProperty(CommonClientConfigKey.NFLoadBalancerRuleClassName, "com.netflix.loadbalancer.RoundRobinRule");
        clientConfig.setProperty(CommonClientConfigKey.MaxHttpConnectionsPerHost, RibbonPropertyHelper.getRibbonMaxHttpConnectionsPerHost(serviceName));
        clientConfig.setProperty(CommonClientConfigKey.MaxTotalHttpConnections, RibbonPropertyHelper.getRibbonMaxTotalHttpConnections(serviceName));
        clientConfig.setProperty(CommonClientConfigKey.MaxAutoRetries, RibbonPropertyHelper.getRibbonMaxAutoRetries(serviceName));
        clientConfig.setProperty(CommonClientConfigKey.MaxAutoRetriesNextServer, RibbonPropertyHelper.getRibbonMaxAutoRetriesNextServer(serviceName));
//        clientConfig.setProperty(CommonClientConfigKey.ConnectTimeout, RibbonPropertyHelper.getRibbonConnectTimeout(routeGroup, routeName));
//        clientConfig.setProperty(CommonClientConfigKey.ReadTimeout, RibbonPropertyHelper.getRibbonReadTimeout(routeGroup, routeName));
        clientConfig.setProperty(CommonClientConfigKey.RequestSpecificRetryOn, true);
        clientConfig.setProperty(CommonClientConfigKey.DeploymentContextBasedVipAddresses,
                serviceName)

        return clientConfig;
    }

    private HttpRequest.Verb getVerb(String verb) {
        switch (verb) {
            case "POST":
                return HttpRequest.Verb.POST;
            case "PUT":
                return HttpRequest.Verb.PUT;
            case "DELETE":
                return HttpRequest.Verb.DELETE;
            case "HEAD":
                return HttpRequest.Verb.HEAD;
            case "OPTIONS":
                return HttpRequest.Verb.OPTIONS;
            case "GET":
                return HttpRequest.Verb.GET;
            default:
                return HttpRequest.Verb.GET;
        }
    }
}

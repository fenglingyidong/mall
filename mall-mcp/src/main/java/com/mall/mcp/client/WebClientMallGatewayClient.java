package com.mall.mcp.client;

import com.mall.mcp.config.MallMcpProperties;
import com.mall.mcp.model.AddCartItemRequest;
import com.mall.mcp.model.ApiEnvelope;
import com.mall.mcp.model.CartItem;
import com.mall.mcp.model.ConfirmOrderResponse;
import com.mall.mcp.model.CreateOrderRequest;
import com.mall.mcp.model.LoginRequest;
import com.mall.mcp.model.LoginResponse;
import com.mall.mcp.model.OrderInfo;
import com.mall.mcp.model.ProductDetail;
import com.mall.mcp.model.ProductSearchItem;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Component
public class WebClientMallGatewayClient implements MallGatewayClient {

    private static final ParameterizedTypeReference<ApiEnvelope<List<ProductSearchItem>>> SEARCH_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<ProductDetail>> DETAIL_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<LoginResponse>> LOGIN_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<CartItem>> CART_ITEM_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<List<CartItem>>> CART_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<ConfirmOrderResponse>> CONFIRM_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<OrderInfo>> ORDER_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final Duration requestTimeout;

    public WebClientMallGatewayClient(WebClient.Builder builder, MallMcpProperties properties) {
        this.webClient = builder.baseUrl(trimTrailingSlash(properties.getMallBaseUrl())).build();
        this.requestTimeout = properties.getRequestTimeout();
    }

    @Override
    public List<ProductSearchItem> searchProducts(String keyword,
                                                  Long categoryId,
                                                  String brand,
                                                  BigDecimal minPrice,
                                                  BigDecimal maxPrice,
                                                  Integer limit) {
        List<ProductSearchItem> data = get(
                uriBuilder -> productSearchUri(uriBuilder, keyword, categoryId, brand, minPrice, maxPrice, limit),
                SEARCH_TYPE
        );
        return data == null ? List.of() : data;
    }

    @Override
    public ProductDetail getProductDetail(Long skuId) {
        return get("/api/product/{skuId}", DETAIL_TYPE, skuId);
    }

    @Override
    public Optional<LoginResponse> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Optional.empty();
        }
        try {
            LoginResponse response = post("/api/auth/login", new LoginRequest(username.trim(), password), LOGIN_TYPE);
            return Optional.ofNullable(response);
        }
        catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public CartItem addToCart(String authorization, AddCartItemRequest request) {
        return authorizedPost("/api/cart/items", authorization, request, CART_ITEM_TYPE);
    }

    @Override
    public List<CartItem> viewCart(String authorization) {
        List<CartItem> data = authorizedGet("/api/cart", authorization, CART_LIST_TYPE);
        return data == null ? List.of() : data;
    }

    @Override
    public ConfirmOrderResponse confirmOrder(String authorization) {
        return authorizedPost("/api/order/confirm", authorization, null, CONFIRM_TYPE);
    }

    @Override
    public OrderInfo createOrder(String authorization, CreateOrderRequest request) {
        CreateOrderRequest safeRequest = request == null ? new CreateOrderRequest(null) : request;
        return authorizedPost("/api/order/create", authorization, safeRequest, ORDER_TYPE);
    }

    private URI productSearchUri(UriBuilder uriBuilder,
                                 String keyword,
                                 Long categoryId,
                                 String brand,
                                 BigDecimal minPrice,
                                 BigDecimal maxPrice,
                                 Integer limit) {
        UriBuilder builder = uriBuilder.path("/api/product/search");
        if (StringUtils.hasText(keyword)) {
            builder.queryParam("keyword", keyword.trim());
        }
        if (categoryId != null) {
            builder.queryParam("categoryId", categoryId);
        }
        if (StringUtils.hasText(brand)) {
            builder.queryParam("brand", brand.trim());
        }
        if (minPrice != null) {
            builder.queryParam("minPrice", minPrice);
        }
        if (maxPrice != null) {
            builder.queryParam("maxPrice", maxPrice);
        }
        if (limit != null) {
            builder.queryParam("limit", limit);
        }
        return builder.build();
    }

    private <T> T get(String uriTemplate, ParameterizedTypeReference<ApiEnvelope<T>> responseType, Object... uriVariables) {
        return execute(webClient.get().uri(uriTemplate, uriVariables), responseType);
    }

    private <T> T get(Function<UriBuilder, URI> uriFunction, ParameterizedTypeReference<ApiEnvelope<T>> responseType) {
        return execute(webClient.get().uri(uriFunction), responseType);
    }

    private <T> T authorizedGet(String uriTemplate, String authorization, ParameterizedTypeReference<ApiEnvelope<T>> responseType) {
        return execute(webClient.get()
                .uri(uriTemplate)
                .headers(headers -> setAuthorization(headers, authorization)), responseType);
    }

    private <T> T post(String uri, Object body, ParameterizedTypeReference<ApiEnvelope<T>> responseType) {
        return execute(webClient.post().uri(uri).bodyValue(body), responseType);
    }

    private <T> T authorizedPost(String uri, String authorization, Object body,
                                 ParameterizedTypeReference<ApiEnvelope<T>> responseType) {
        RequestBodySpec request = webClient.post().uri(uri);
        request.headers(headers -> setAuthorization(headers, authorization));
        return execute(body == null ? request : request.bodyValue(body), responseType);
    }

    private <T> T execute(RequestHeadersSpec<?> request, ParameterizedTypeReference<ApiEnvelope<T>> responseType) {
        return unwrap(request.retrieve()
                .bodyToMono(responseType)
                .block(requestTimeout));
    }

    private <T> T unwrap(ApiEnvelope<T> response) {
        if (response == null) {
            throw new MallGatewayException(500, "Empty mall response");
        }
        if (response.code() != 0) {
            throw new MallGatewayException(response.code(), response.message());
        }
        return response.data();
    }

    private void setAuthorization(HttpHeaders headers, String authorization) {
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, normalizeToken(authorization));
        }
    }

    private String normalizeToken(String authorization) {
        String trimmed = authorization == null ? "" : authorization.trim();
        return trimmed.startsWith("Bearer ") ? trimmed : "Bearer " + trimmed;
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "http://localhost:8100";
        }
        return value.trim().replaceAll("/+$", "");
    }
}

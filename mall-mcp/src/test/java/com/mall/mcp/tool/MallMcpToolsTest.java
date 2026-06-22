package com.mall.mcp.tool;

import com.mall.mcp.client.MallGatewayClient;
import com.mall.mcp.config.MallMcpProperties;
import com.mall.mcp.context.MallContextService;
import com.mall.mcp.model.AddCartItemRequest;
import com.mall.mcp.model.CartItem;
import com.mall.mcp.model.ConfirmOrderResponse;
import com.mall.mcp.model.OrderItem;
import com.mall.mcp.model.ProductDetail;
import com.mall.mcp.model.ProductSearchItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallMcpToolsTest {

    @Test
    void searchProductsShouldReturnToyBlockCandidate() {
        MallGatewayClient client = mock(MallGatewayClient.class);
        MallMcpTools tools = newTools(client, defaultProperties());
        when(client.searchProducts("积木", null, null, null, null, 10))
                .thenReturn(List.of(new ProductSearchItem(
                        3020L,
                        111L,
                        "儿童积木套装 300片",
                        "儿童积木套装",
                        "启蒙",
                        "玩具",
                        new BigDecimal("149.00"),
                        120
                )));

        MallToolResponse response = tools.searchProducts("积木", null, null, null, null, null);

        assertThat(response.ok()).isTrue();
        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data()).asList()
                .extracting("skuName", "price")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("儿童积木套装 300片", new BigDecimal("149.00")));
    }

    @Test
    void addToCartShouldFillRealNameAndPriceFromProductDetail() {
        MallGatewayClient client = mock(MallGatewayClient.class);
        MallMcpTools tools = newTools(client, defaultProperties());
        when(client.getProductDetail(3020L)).thenReturn(new ProductDetail(
                3020L,
                111L,
                "儿童积木套装 300片",
                "儿童积木套装",
                "玩具",
                "启蒙",
                new BigDecimal("149.00"),
                20,
                null,
                List.of(),
                null,
                List.of()
        ));
        when(client.addToCart(eq("Bearer token"), org.mockito.ArgumentMatchers.any(AddCartItemRequest.class)))
                .thenReturn(new CartItem(3020L, "儿童积木套装 300片", new BigDecimal("149.00"), 2, true));

        MallToolResponse response = tools.addToCart(context("shopping-demo", "Bearer token"), 3020L, 2);

        assertThat(response.ok()).isTrue();
        ArgumentCaptor<AddCartItemRequest> captor = ArgumentCaptor.forClass(AddCartItemRequest.class);
        verify(client).addToCart(eq("Bearer token"), captor.capture());
        assertThat(captor.getValue().skuName()).isEqualTo("儿童积木套装 300片");
        assertThat(captor.getValue().price()).isEqualByComparingTo("149.00");
        assertThat(captor.getValue().quantity()).isEqualTo(2);
    }

    @Test
    void viewCartWithoutContextShouldReturnAuthRequired() {
        MallMcpTools tools = newTools(mock(MallGatewayClient.class), defaultProperties());

        MallToolResponse response = tools.viewCart(context("missing-session", ""));

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void createOrderWithoutPrepareShouldReturnConfirmationRequired() {
        MallGatewayClient client = mock(MallGatewayClient.class);
        MallMcpTools tools = newTools(client, defaultProperties());

        MallToolResponse response = tools.createOrder(context("shopping-demo", "Bearer token"),
                "missing-confirmation", true, "remark");

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void createOrderWithExpiredConfirmationShouldReturnConfirmationExpired() {
        MallGatewayClient client = mock(MallGatewayClient.class);
        MallMcpProperties properties = defaultProperties();
        properties.setConfirmationTtl(Duration.ZERO);
        MallMcpTools tools = newTools(client, properties);
        when(client.confirmOrder("Bearer token")).thenReturn(confirmOrder());

        MallToolResponse prepareResponse = tools.prepareOrder(context("shopping-demo", "Bearer token"));
        String confirmationId = (String) ((java.util.Map<?, ?>) prepareResponse.data()).get("confirmationId");
        MallToolResponse createResponse = tools.createOrder(context("shopping-demo", "Bearer token"),
                confirmationId, true, "remark");

        assertThat(createResponse.ok()).isFalse();
        assertThat(createResponse.code()).isEqualTo("CONFIRMATION_EXPIRED");
    }

    private MallMcpTools newTools(MallGatewayClient client, MallMcpProperties properties) {
        MallContextService contextService = new MallContextService(properties, client);
        OrderConfirmationService confirmationService = new OrderConfirmationService(properties);
        return new TestableMallMcpTools(client, contextService, confirmationService);
    }

    private McpSyncRequestContext context(String sessionId, String mallToken) {
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.requestMeta()).thenReturn(StringUtils.hasText(mallToken)
                ? Map.of("sessionId", sessionId, "userId", "10001", "mallToken", mallToken)
                : Map.of("sessionId", sessionId, "userId", "10001"));
        when(context.sessionId()).thenReturn(sessionId);
        return context;
    }

    private ConfirmOrderResponse confirmOrder() {
        return new ConfirmOrderResponse(
                10001L,
                List.of(new OrderItem(3020L, "儿童积木套装 300片", new BigDecimal("149.00"), 1, new BigDecimal("149.00"))),
                new BigDecimal("149.00")
        );
    }

    private MallMcpProperties defaultProperties() {
        MallMcpProperties properties = new MallMcpProperties();
        properties.setContextTtl(Duration.ofMinutes(30));
        properties.setConfirmationTtl(Duration.ofMinutes(5));
        return properties;
    }

    private static final class TestableMallMcpTools extends MallMcpTools {

        private TestableMallMcpTools(MallGatewayClient mallGatewayClient,
                                     MallContextService contextService,
                                     OrderConfirmationService confirmationService) {
            super(mallGatewayClient, contextService, confirmationService);
        }
    }
}

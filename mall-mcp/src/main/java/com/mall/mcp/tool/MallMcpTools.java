package com.mall.mcp.tool;

import com.mall.mcp.client.MallGatewayClient;
import com.mall.mcp.client.MallGatewayException;
import com.mall.mcp.context.MallContextService;
import com.mall.mcp.model.AddCartItemRequest;
import com.mall.mcp.model.ConfirmOrderResponse;
import com.mall.mcp.model.CreateOrderRequest;
import com.mall.mcp.model.OrderInfo;
import com.mall.mcp.model.OrderItem;
import com.mall.mcp.model.ProductDetail;
import com.mall.mcp.model.ProductSearchItem;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class MallMcpTools {

    private final MallGatewayClient mallGatewayClient;
    private final MallContextService contextService;
    private final OrderConfirmationService confirmationService;

    public MallMcpTools(MallGatewayClient mallGatewayClient,
                        MallContextService contextService,
                        OrderConfirmationService confirmationService) {
        this.mallGatewayClient = mallGatewayClient;
        this.contextService = contextService;
        this.confirmationService = confirmationService;
    }

    @McpTool(name = "mall_search_products", description = "Search mall products by keyword, category, brand and price range.")
    public MallToolResponse searchProducts(
            @McpToolParam(required = false, description = "Search keyword, for example 积木") String keyword,
            @McpToolParam(required = false, description = "Category id") Long categoryId,
            @McpToolParam(required = false, description = "Brand name") String brand,
            @McpToolParam(required = false, description = "Minimum price") BigDecimal minPrice,
            @McpToolParam(required = false, description = "Maximum price") BigDecimal maxPrice,
            @McpToolParam(required = false, description = "Maximum result count") Integer limit) {
        return safe(() -> MallToolResponse.ok(mallGatewayClient.searchProducts(
                keyword,
                categoryId,
                brand,
                minPrice,
                maxPrice,
                limit == null ? 10 : limit
        )));
    }

    @McpTool(name = "mall_get_product_detail", description = "Get real-time mall product detail by SKU id.")
    public MallToolResponse getProductDetail(
            @McpToolParam(description = "SKU id") Long skuId) {
        return safe(() -> {
            if (skuId == null) {
                return MallToolResponse.error(MallToolCode.NOT_FOUND, "skuId is required");
            }
            ProductDetail detail = mallGatewayClient.getProductDetail(skuId);
            return detail == null
                    ? MallToolResponse.error(MallToolCode.NOT_FOUND, "product not found")
                    : MallToolResponse.ok(detail);
        });
    }

    @McpTool(name = "mall_add_to_cart", description = "Add a SKU to the current user's cart. Price and name are always resolved from mall product detail.")
    public MallToolResponse addToCart(
            McpSyncRequestContext requestContext,
            @McpToolParam(description = "SKU id") Long skuId,
            @McpToolParam(description = "Quantity to add") Integer quantity) {
        return safe(() -> {
            String sessionId = sessionId(requestContext);
            Optional<String> authorization = contextService.resolveAuthorization(sessionId, requestMeta(requestContext));
            if (authorization.isEmpty()) {
                return MallToolResponse.error(MallToolCode.AUTH_REQUIRED, "valid mall authorization is required");
            }
            if (skuId == null || quantity == null || quantity < 1) {
                return MallToolResponse.error(MallToolCode.MALL_ERROR, "skuId and positive quantity are required");
            }
            ProductDetail detail = mallGatewayClient.getProductDetail(skuId);
            if (detail == null) {
                return MallToolResponse.error(MallToolCode.NOT_FOUND, "product not found");
            }
            if (detail.stock() != null && detail.stock() < quantity) {
                return MallToolResponse.error(MallToolCode.STOCK_NOT_ENOUGH, "stock is not enough");
            }
            AddCartItemRequest request = new AddCartItemRequest(
                    detail.skuId(),
                    firstText(detail.skuName(), detail.spuName(), "SKU-" + detail.skuId()),
                    detail.price(),
                    quantity
            );
            return MallToolResponse.ok(mallGatewayClient.addToCart(authorization.get(), request));
        });
    }

    @McpTool(name = "mall_view_cart", description = "View the current user's cart.")
    public MallToolResponse viewCart(
            McpSyncRequestContext requestContext) {
        return safe(() -> {
            String sessionId = sessionId(requestContext);
            Optional<String> authorization = contextService.resolveAuthorization(sessionId, requestMeta(requestContext));
            if (authorization.isEmpty()) {
                return MallToolResponse.error(MallToolCode.AUTH_REQUIRED, "valid mall authorization is required");
            }
            return MallToolResponse.ok(mallGatewayClient.viewCart(authorization.get()));
        });
    }

    @McpTool(name = "mall_prepare_order", description = "Confirm the current user's selected cart items and create a short-lived order confirmation id.")
    public MallToolResponse prepareOrder(
            McpSyncRequestContext requestContext) {
        return safe(() -> {
            String sessionId = sessionId(requestContext);
            Optional<String> authorization = contextService.resolveAuthorization(sessionId, requestMeta(requestContext));
            if (authorization.isEmpty()) {
                return MallToolResponse.error(MallToolCode.AUTH_REQUIRED, "valid mall authorization is required");
            }
            ConfirmOrderResponse confirm = mallGatewayClient.confirmOrder(authorization.get());
            OrderConfirmation confirmation = confirmationService.prepare(sessionId, confirm);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("confirmationId", confirmation.confirmationId());
            data.put("expiresAt", confirmation.expiresAt().toString());
            data.put("order", confirm);
            return MallToolResponse.ok(data);
        });
    }

    @McpTool(name = "mall_create_order", description = "Create a normal order after mall_prepare_order and explicit user confirmation.")
    public MallToolResponse createOrder(
            McpSyncRequestContext requestContext,
            @McpToolParam(description = "Confirmation id returned by mall_prepare_order") String confirmationId,
            @McpToolParam(description = "Must be true only after explicit user confirmation") Boolean userConfirmed,
            @McpToolParam(required = false, description = "Optional order remark") String remark) {
        return safe(() -> {
            String sessionId = sessionId(requestContext);
            Optional<String> authorization = contextService.resolveAuthorization(sessionId, requestMeta(requestContext));
            if (authorization.isEmpty()) {
                return MallToolResponse.error(MallToolCode.AUTH_REQUIRED, "valid mall authorization is required");
            }
            if (!Boolean.TRUE.equals(userConfirmed)) {
                return MallToolResponse.error(MallToolCode.CONFIRMATION_REQUIRED, "explicit user confirmation is required");
            }
            Optional<OrderConfirmation> confirmationOptional = confirmationService.find(sessionId, confirmationId);
            if (confirmationOptional.isEmpty()) {
                return MallToolResponse.error(MallToolCode.CONFIRMATION_REQUIRED, "order confirmation is required");
            }
            OrderConfirmation confirmation = confirmationOptional.get();
            if (confirmationService.isExpired(confirmation)) {
                confirmationService.remove(confirmation.confirmationId());
                return MallToolResponse.error(MallToolCode.CONFIRMATION_EXPIRED, "order confirmation has expired");
            }
            ConfirmOrderResponse latestConfirm = mallGatewayClient.confirmOrder(authorization.get());
            if (!sameOrder(confirmation.snapshot(), latestConfirm)) {
                confirmationService.remove(confirmation.confirmationId());
                return MallToolResponse.error(MallToolCode.CONFIRMATION_REQUIRED, "cart changed, please prepare order again");
            }
            OrderInfo order = mallGatewayClient.createOrder(authorization.get(), new CreateOrderRequest(remark));
            confirmationService.remove(confirmation.confirmationId());
            return MallToolResponse.ok(order);
        });
    }

    private MallToolResponse safe(Supplier<MallToolResponse> supplier) {
        try {
            return supplier.get();
        }
        catch (MallGatewayException ex) {
            return MallToolResponse.error(mapMallCode(ex), ex.getMessage());
        }
        catch (RuntimeException ex) {
            return MallToolResponse.error(MallToolCode.MALL_ERROR, ex.getMessage());
        }
    }

    private MallToolCode mapMallCode(MallGatewayException ex) {
        if (ex.mallCode() == 401) {
            return MallToolCode.AUTH_REQUIRED;
        }
        if (ex.mallCode() == 404) {
            return MallToolCode.NOT_FOUND;
        }
        if (ex.mallCode() == 409 && ex.getMessage() != null
                && ex.getMessage().toLowerCase().contains("stock")) {
            return MallToolCode.STOCK_NOT_ENOUGH;
        }
        return MallToolCode.MALL_ERROR;
    }

    private boolean sameOrder(ConfirmOrderResponse left, ConfirmOrderResponse right) {
        if (left == null || right == null) {
            return false;
        }
        if (!sameAmount(left.totalAmount(), right.totalAmount())) {
            return false;
        }
        List<OrderItem> leftItems = sortedItems(left.items());
        List<OrderItem> rightItems = sortedItems(right.items());
        if (leftItems.size() != rightItems.size()) {
            return false;
        }
        for (int i = 0; i < leftItems.size(); i++) {
            OrderItem leftItem = leftItems.get(i);
            OrderItem rightItem = rightItems.get(i);
            if (!java.util.Objects.equals(leftItem.skuId(), rightItem.skuId())
                    || !java.util.Objects.equals(leftItem.quantity(), rightItem.quantity())
                    || !sameAmount(leftItem.price(), rightItem.price())
                    || !sameAmount(leftItem.amount(), rightItem.amount())) {
                return false;
            }
        }
        return true;
    }

    private String sessionId(McpSyncRequestContext requestContext) {
        Object value = requestMeta(requestContext).get("sessionId");
        if (value != null && StringUtils.hasText(value.toString())) {
            return value.toString().trim();
        }
        return requestContext == null ? "" : requestContext.sessionId();
    }

    private Map<String, Object> requestMeta(McpSyncRequestContext requestContext) {
        if (requestContext == null || requestContext.requestMeta() == null) {
            return Map.of();
        }
        return requestContext.requestMeta();
    }

    private List<OrderItem> sortedItems(List<OrderItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator.comparing(OrderItem::skuId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}

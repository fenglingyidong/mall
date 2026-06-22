@echo off
start "mall-auth" java -jar mall-auth/target/mall-auth-0.0.1-SNAPSHOT.jar
start "mall-review" java -jar mall-review/target/mall-review-0.0.1-SNAPSHOT.jar
start "mall-coupon" java -jar mall-coupon/target/mall-coupon-0.0.1-SNAPSHOT.jar
start "mall-product" java -jar mall-product/target/mall-product-0.0.1-SNAPSHOT.jar
start "mall-cart" java -jar mall-cart/target/mall-cart-0.0.1-SNAPSHOT.jar
start "mall-order" java -jar mall-order/target/mall-order-0.0.1-SNAPSHOT.jar
start "mall-seckill" java -jar mall-seckill/target/mall-seckill-0.0.1-SNAPSHOT.jar
start "mall-gateway" java -jar mall-gateway/target/mall-gateway-0.0.1-SNAPSHOT.jar
start "mall-mcp" java -jar mall-mcp/target/mall-mcp-0.0.1-SNAPSHOT.jar
echo 所有服务已启动，请查看各窗口日志。
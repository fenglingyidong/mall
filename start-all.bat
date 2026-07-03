@echo off
setlocal

echo 启动基础中间件...
docker compose up -d nginx mysql redis rabbitmq nacos sentinel seata canal
if errorlevel 1 (
    echo Docker Compose 启动失败，请先检查 Docker Desktop 是否运行。
    exit /b 1
)

echo 等待 RabbitMQ 监听 localhost:5672...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(90); while ((Get-Date) -lt $deadline) { try { $client=New-Object Net.Sockets.TcpClient; $connected=$client.ConnectAsync('localhost', 5672).Wait(1000); $client.Close(); if ($connected) { exit 0 } } catch { }; Start-Sleep -Seconds 2 }; exit 1"
if errorlevel 1 (
    echo RabbitMQ 未在 90 秒内就绪，请检查 docker compose logs rabbitmq。
    exit /b 1
)

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

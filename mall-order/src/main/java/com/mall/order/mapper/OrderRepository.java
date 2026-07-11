package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.model.OrderStatus;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.pojo.entity.OrderInfoEntity;
import com.mall.order.pojo.entity.OrderItem;
import com.mall.order.pojo.entity.OrderItemEntity;
import com.mall.order.pojo.entity.SeckillOrderEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final SeckillOrderMapper seckillOrderMapper;

    public OrderRepository(OrderInfoMapper orderInfoMapper,
                           OrderItemMapper orderItemMapper,
                           SeckillOrderMapper seckillOrderMapper) {
        this.orderInfoMapper = orderInfoMapper;
        this.orderItemMapper = orderItemMapper;
        this.seckillOrderMapper = seckillOrderMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderInfo save(OrderInfo order) {
        OrderInfoEntity entity = toEntity(order);
        orderInfoMapper.insert(entity);
        for (OrderItem item : order.items()) {
            orderItemMapper.insert(toEntity(order.orderSn(), item));
        }
        return order;
    }

    public OrderInfo require(String orderSn) {
        OrderInfoEntity entity = orderInfoMapper.selectOne(Wrappers.<OrderInfoEntity>lambdaQuery()
                .eq(OrderInfoEntity::getOrderSn, orderSn));
        if (entity == null) {
            throw new BusinessException(404, "Order not found");
        }
        return toDomain(entity, findItems(orderSn));
    }

    public Optional<OrderInfo> findSeckillOrder(Long activityId, Long userId) {
        SeckillOrderEntity seckillOrder = seckillOrderMapper.selectOne(Wrappers.<SeckillOrderEntity>lambdaQuery()
                .eq(SeckillOrderEntity::getActivityId, activityId)
                .eq(SeckillOrderEntity::getUserId, userId));
        if (seckillOrder == null) {
            return Optional.empty();
        }
        return Optional.of(require(seckillOrder.getOrderSn()));
    }

    public Optional<OrderInfo> findBySource(String source, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return Optional.empty();
        }
        OrderInfoEntity entity = orderInfoMapper.selectOne(Wrappers.<OrderInfoEntity>lambdaQuery()
                .eq(OrderInfoEntity::getSource, source)
                .eq(OrderInfoEntity::getSourceId, sourceId));
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(entity, findItems(entity.getOrderSn())));
    }

    public void bindSeckill(Long activityId, Long userId, Long skuId, String orderSn) {
        bindSeckill(null, activityId, userId, skuId, orderSn, null);
    }

    public void bindSeckill(String reservationId,
                            Long activityId,
                            Long userId,
                            Long skuId,
                            String orderSn,
                            Long bucketShardKey) {
        SeckillOrderEntity entity = new SeckillOrderEntity();
        entity.setReservationId(reservationId);
        entity.setActivityId(activityId);
        entity.setUserId(userId);
        entity.setSkuId(skuId);
        entity.setOrderSn(orderSn);
        entity.setBucketShardKey(bucketShardKey);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            seckillOrderMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(409, "Duplicate seckill purchase");
        }
    }

    public Optional<SeckillReservationBinding> findSeckillBinding(String orderSn) {
        SeckillOrderEntity entity = seckillOrderMapper.selectOne(Wrappers.<SeckillOrderEntity>lambdaQuery()
                .eq(SeckillOrderEntity::getOrderSn, orderSn));
        if (entity == null || entity.getReservationId() == null) {
            return Optional.empty();
        }
        return Optional.of(new SeckillReservationBinding(
                entity.getReservationId(),
                entity.getOrderSn(),
                entity.getBucketShardKey()));
    }

    public List<OrderInfo> findExpiredSeckillCreatedOrders(Instant now, int limit) {
        int safeLimit = Math.max(1, limit);
        LocalDateTime expireAt = toLocalDateTime(now);
        return orderInfoMapper.selectList(Wrappers.<OrderInfoEntity>lambdaQuery()
                        .eq(OrderInfoEntity::getSource, "SECKILL")
                        .eq(OrderInfoEntity::getStatus, OrderStatus.CREATED.name())
                        .le(OrderInfoEntity::getPayExpireAt, expireAt)
                        .orderByAsc(OrderInfoEntity::getPayExpireAt)
                        .orderByAsc(OrderInfoEntity::getId)
                        .last("LIMIT " + safeLimit))
                .stream()
                .map(entity -> toDomain(entity, findItems(entity.getOrderSn())))
                .toList();
    }

    public Optional<OrderInfo> closeExpiredSeckillOrder(String orderSn, Instant now) {
        LocalDateTime expireAt = toLocalDateTime(now);
        int updated = orderInfoMapper.update(null, Wrappers.<OrderInfoEntity>lambdaUpdate()
                .eq(OrderInfoEntity::getOrderSn, orderSn)
                .eq(OrderInfoEntity::getSource, "SECKILL")
                .eq(OrderInfoEntity::getStatus, OrderStatus.CREATED.name())
                .le(OrderInfoEntity::getPayExpireAt, expireAt)
                .set(OrderInfoEntity::getStatus, OrderStatus.CLOSED.name())
                .set(OrderInfoEntity::getUpdatedAt, expireAt));
        if (updated <= 0) {
            return Optional.empty();
        }
        return Optional.of(require(orderSn));
    }

    public OrderInfo transition(String orderSn, OrderStatus from, OrderStatus to) {
        orderInfoMapper.update(null, Wrappers.<OrderInfoEntity>lambdaUpdate()
                .eq(OrderInfoEntity::getOrderSn, orderSn)
                .eq(OrderInfoEntity::getStatus, from.name())
                .set(OrderInfoEntity::getStatus, to.name())
                .set(OrderInfoEntity::getUpdatedAt, LocalDateTime.now()));
        return require(orderSn);
    }

    public Collection<OrderInfo> findAll() {
        return orderInfoMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(entity -> toDomain(entity, findItems(entity.getOrderSn())))
                .toList();
    }

    private List<OrderItem> findItems(String orderSn) {
        return orderItemMapper.selectList(Wrappers.<OrderItemEntity>lambdaQuery()
                        .eq(OrderItemEntity::getOrderSn, orderSn))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private OrderInfoEntity toEntity(OrderInfo order) {
        OrderInfoEntity entity = new OrderInfoEntity();
        entity.setOrderSn(order.orderSn());
        entity.setUserId(order.userId());
        entity.setStatus(order.status().name());
        entity.setTotalAmount(order.totalAmount());
        entity.setSource(order.source());
        entity.setSourceId(order.sourceId());
        entity.setPayExpireAt(toLocalDateTime(order.payExpireAt()));
        entity.setCreatedAt(toLocalDateTime(order.createdAt()));
        entity.setUpdatedAt(toLocalDateTime(order.updatedAt()));
        return entity;
    }

    private OrderItemEntity toEntity(String orderSn, OrderItem item) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setOrderSn(orderSn);
        entity.setSkuId(item.skuId());
        entity.setSkuName(item.skuName());
        entity.setPrice(item.price());
        entity.setQuantity(item.quantity());
        entity.setAmount(item.amount());
        return entity;
    }

    private OrderInfo toDomain(OrderInfoEntity entity, List<OrderItem> items) {
        return new OrderInfo(
                entity.getOrderSn(),
                entity.getUserId(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getTotalAmount(),
                items,
                entity.getSource(),
                entity.getSourceId(),
                toInstant(entity.getPayExpireAt()),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt())
        );
    }

    private OrderItem toDomain(OrderItemEntity entity) {
        return new OrderItem(entity.getSkuId(), entity.getSkuName(), entity.getPrice(), entity.getQuantity(), entity.getAmount());
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZONE_ID);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE_ID).toInstant();
    }

    public record SeckillReservationBinding(String reservationId,
                                            String orderSn,
                                            Long bucketShardKey) {
    }
}

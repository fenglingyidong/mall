import http from './http'

export function login(payload) {
  return http.post('/api/auth/login', payload)
}

export function getProduct(skuId) {
  return http.get(`/api/product/${skuId}`)
}

export function getCategoryTree() {
  return http.get('/api/product/category/tree')
}

export function getCart() {
  return http.get('/api/cart')
}

export function addCartItem(payload) {
  return http.post('/api/cart/items', payload)
}

export function updateCartItem(skuId, payload) {
  return http.put(`/api/cart/items/${skuId}`, payload)
}

export function removeCartItem(skuId) {
  return http.delete(`/api/cart/items/${skuId}`)
}

export function confirmOrder() {
  return http.post('/api/order/confirm')
}

export function createOrder(payload = {}) {
  return http.post('/api/order/create', payload)
}

export function getOrder(orderSn) {
  return http.get(`/api/order/${orderSn}`)
}

export function payOrder(orderSn) {
  return http.post(`/api/order/${orderSn}/pay`)
}

export function cancelOrder(orderSn) {
  return http.post(`/api/order/${orderSn}/cancel`)
}

export function getSeckillActivities() {
  return http.get('/api/seckill/activities')
}

export function submitSeckill(activityId, skuId) {
  return http.post(`/api/seckill/${activityId}/${skuId}`)
}

export function getSeckillResult(requestId) {
  return http.get(`/api/seckill/result/${requestId}`)
}

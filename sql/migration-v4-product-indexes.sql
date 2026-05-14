ALTER TABLE category
    ADD INDEX idx_category_parent (parent_id);

ALTER TABLE spu
    ADD INDEX idx_spu_category (category_id),
    ADD INDEX idx_spu_brand (brand_id);

ALTER TABLE sku
    ADD INDEX idx_sku_spu (spu_id);

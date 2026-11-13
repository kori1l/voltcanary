CREATE TABLE cfg_circle_feature (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    head_revision BIGINT NOT NULL CHECK (head_revision > 0)
);
CREATE INDEX ix_cfg_circle_feature_account ON cfg_circle_feature(account_id);

CREATE TABLE cfg_circle_feature_revision (
    feature_id UUID NOT NULL REFERENCES cfg_circle_feature(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    radius_metres VARCHAR(32) NOT NULL,
    digest VARCHAR(64) NOT NULL CHECK (LENGTH(digest)=64),
    PRIMARY KEY (feature_id,revision)
);

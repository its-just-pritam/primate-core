--liquibase formatted sql

--changeset platito:002-ingredient
-- Shared catalog. Nutrient values are per base_quantity of unit.
CREATE TABLE ingredient (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT             NOT NULL,
    base_quantity DOUBLE PRECISION NOT NULL DEFAULT 1,
    unit          TEXT             NOT NULL DEFAULT '',
    calories      DOUBLE PRECISION NOT NULL DEFAULT 0,
    protein       DOUBLE PRECISION NOT NULL DEFAULT 0,
    carbs         DOUBLE PRECISION NOT NULL DEFAULT 0,
    fats          DOUBLE PRECISION NOT NULL DEFAULT 0,
    calcium       DOUBLE PRECISION NOT NULL DEFAULT 0,
    iron          DOUBLE PRECISION NOT NULL DEFAULT 0,
    potassium     DOUBLE PRECISION NOT NULL DEFAULT 0,
    sodium        DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_c     DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_b12   DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_d     DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT ux_ingredient_name UNIQUE (name)
);
--rollback DROP TABLE ingredient;

--changeset platito:002-recipe
CREATE TABLE recipe (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_recipe_user_name UNIQUE (user_id, name)
);

-- quantity is a multiple of the ingredient's base quantity (e.g. 1.5 x 100 g).
CREATE TABLE recipe_ingredient (
    id            BIGSERIAL PRIMARY KEY,
    recipe_id     BIGINT           NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    ingredient_id BIGINT           NOT NULL REFERENCES ingredient (id),
    quantity      DOUBLE PRECISION NOT NULL CHECK (quantity > 0),
    position      INT              NOT NULL
);
CREATE INDEX ix_recipe_ingredient_recipe ON recipe_ingredient (recipe_id, position);
--rollback DROP TABLE recipe_ingredient; DROP TABLE recipe;

--changeset platito:002-packaged-food
-- Nutrient values are for `quantity` servings.
CREATE TABLE packaged_food (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT           NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name        TEXT             NOT NULL,
    quantity    DOUBLE PRECISION NOT NULL DEFAULT 1 CHECK (quantity > 0),
    calories    DOUBLE PRECISION NOT NULL DEFAULT 0,
    protein     DOUBLE PRECISION NOT NULL DEFAULT 0,
    carbs       DOUBLE PRECISION NOT NULL DEFAULT 0,
    fats        DOUBLE PRECISION NOT NULL DEFAULT 0,
    calcium     DOUBLE PRECISION NOT NULL DEFAULT 0,
    iron        DOUBLE PRECISION NOT NULL DEFAULT 0,
    potassium   DOUBLE PRECISION NOT NULL DEFAULT 0,
    sodium      DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_c   DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_b12 DOUBLE PRECISION NOT NULL DEFAULT 0,
    vitamin_d   DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT ux_packaged_food_user_name UNIQUE (user_id, name)
);
--rollback DROP TABLE packaged_food;

--changeset platito:002-meal-log
-- dish names a recipe or packaged food owned by the user; nutrition is computed on read.
CREATE TABLE meal_log (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT           NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    dish      TEXT             NOT NULL,
    portion   DOUBLE PRECISION NOT NULL CHECK (portion > 0),
    logged_at TIMESTAMPTZ      NOT NULL
);
CREATE INDEX ix_meal_log_user_logged_at ON meal_log (user_id, logged_at);
--rollback DROP TABLE meal_log;

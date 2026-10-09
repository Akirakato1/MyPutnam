PRAGMA foreign_keys = ON;

CREATE TABLE collection (
    id TEXT NOT NULL,
    title TEXT NOT NULL,
    kind TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX index_collection_display_order ON collection (display_order);

CREATE TABLE problem_set (
    collection_id TEXT NOT NULL,
    set_id TEXT NOT NULL,
    title TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    competition_year INTEGER,
    PRIMARY KEY (collection_id, set_id),
    FOREIGN KEY (collection_id) REFERENCES collection (id)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_problem_set_collection_id ON problem_set (collection_id);
CREATE UNIQUE INDEX index_problem_set_collection_id_display_order
    ON problem_set (collection_id, display_order);

CREATE TABLE topic (
    id TEXT NOT NULL,
    label TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX index_topic_display_order ON topic (display_order);

CREATE TABLE problem (
    key TEXT NOT NULL,
    collection_id TEXT NOT NULL,
    set_id TEXT NOT NULL,
    problem_id TEXT NOT NULL,
    label TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    statement_asset_path TEXT NOT NULL,
    statement_sha256 TEXT NOT NULL,
    solution_asset_path TEXT NOT NULL,
    solution_sha256 TEXT NOT NULL,
    attribution_json TEXT NOT NULL,
    PRIMARY KEY (key),
    FOREIGN KEY (collection_id, set_id) REFERENCES problem_set (collection_id, set_id)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_problem_collection_id_set_id ON problem (collection_id, set_id);
CREATE UNIQUE INDEX index_problem_collection_id_set_id_problem_id
    ON problem (collection_id, set_id, problem_id);
CREATE UNIQUE INDEX index_problem_collection_id_set_id_ordinal
    ON problem (collection_id, set_id, ordinal);

CREATE TABLE problem_topic (
    problem_key TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    PRIMARY KEY (problem_key, topic_id),
    FOREIGN KEY (problem_key) REFERENCES problem (key)
        ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY (topic_id) REFERENCES topic (id)
        ON UPDATE NO ACTION ON DELETE RESTRICT
);

CREATE INDEX index_problem_topic_topic_id ON problem_topic (topic_id);

CREATE TABLE hint (
    problem_key TEXT NOT NULL,
    hint_order INTEGER NOT NULL,
    stage TEXT NOT NULL CHECK (stage IN ('nudge', 'strategy', 'knowledge', 'outline')),
    title TEXT NOT NULL,
    asset_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (problem_key, hint_order),
    FOREIGN KEY (problem_key) REFERENCES problem (key)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_hint_problem_key ON hint (problem_key);

CREATE TABLE prerequisite (
    problem_key TEXT NOT NULL,
    prerequisite_order INTEGER NOT NULL,
    title TEXT NOT NULL,
    asset_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (problem_key, prerequisite_order),
    FOREIGN KEY (problem_key) REFERENCES problem (key)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_prerequisite_problem_key ON prerequisite (problem_key);

CREATE TABLE content_meta (
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (key)
);

PRAGMA user_version = 3;

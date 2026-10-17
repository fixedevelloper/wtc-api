-- Rapport de l'ETL de migration (phase 10) : une ligne par anomalie, correction ou décision, avec la valeur d'origine.
-- Table d'exploitation : lue par l'équipe avant la bascule, jamais par l'application.
CREATE TABLE etl_report (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       uuid        NOT NULL,
    stage        text        NOT NULL,
    severity     text        NOT NULL CHECK (severity IN ('INFO', 'WARN', 'ERROR')),
    legacy_table text,
    legacy_id    bigint,
    message      text        NOT NULL,
    original     jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX etl_report_run_idx ON etl_report (run_id, severity, stage);

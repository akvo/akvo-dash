CREATE TABLE IF NOT EXISTS visualisation (
       id text NOT NULL,
       "name" text NOT NULL,
       spec jsonb,
       author jsonb,
       created timestamptz DEFAULT now(),
       modified timestamptz DEFAULT now()
);


GRANT ALL ON visualisation TO dash;

CREATE TRIGGER visualisation_history BEFORE
INSERT OR DELETE OR UPDATE ON visualisation
FOR EACH ROW EXECUTE PROCEDURE history.log_change();
--;;


CREATE TABLE IF NOT EXISTS history.visualisation (
       LIKE public.visualisation,
       _validrange tstzrange NOT NULL
);

GRANT ALL ON history.visualisation TO dash;

ALTER TABLE ONLY history.visualisation
ADD CONSTRAINT visualisation_exclusion EXCLUDE
USING gist (id WITH =, _validrange WITH &&);
--;;


CREATE OR REPLACE FUNCTION to_number(val jsonb, default_val numeric)
  RETURNS jsonb AS
$BODY$
BEGIN
  RETURN CAST (trim(both '"' from val::text) AS numeric);
EXCEPTION
  WHEN invalid_text_representation THEN
    RETURN default_val;
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;


CREATE OR REPLACE FUNCTION to_number(val jsonb, raise_on_error boolean)
  RETURNS jsonb AS
$BODY$
BEGIN
  RETURN CAST (trim(both '"' from val::text) AS numeric);
EXCEPTION
  WHEN invalid_text_representation THEN
    IF raise_on_error THEN
      RAISE EXCEPTION 'Unable to convert value % to number', val;
    ELSE
      RETURN NULL;
    END IF;
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION to_text(val jsonb)
  RETURNS jsonb AS
$BODY$
DECLARE
  tmp text = val::text;
BEGIN
  IF left(tmp, 1) = '"' THEN
    RETURN tmp;
  END IF;
  RETURN '"' || tmp || '"';
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION to_date(val jsonb, format text, raise_on_error boolean)
  RETURNS jsonb AS
$BODY$
DECLARE
  tmp text = trim(both '"' from val::text);
BEGIN
  RETURN date_part('epoch', to_timestamp(tmp, format))::numeric;
EXCEPTION
  WHEN OTHERS THEN
  IF raise_on_error THEN
    RAISE EXCEPTION 'Unable to convert % to date value', val;
  ELSE
    RETURN NULL;
  END IF;
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION to_date(val jsonb, format text)
  RETURNS jsonb AS
$BODY$
BEGIN
  RETURN to_date(val, format, false);
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION titlecase(val jsonb)
  RETURNS jsonb AS
$BODY$
BEGIN
  RETURN initcap(val::text);
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION trim_space(val jsonb)
  RETURNS jsonb AS
$BODY$
BEGIN
  RETURN regexp_replace(val::text, '\s+', ' ', 'g');
END;
$BODY$
  LANGUAGE plpgsql IMMUTABLE STRICT;

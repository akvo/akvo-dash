CREATE TABLE dashboard (
    id text PRIMARY KEY,
    title text NOT NULL,
    spec jsonb,
    created timestamptz DEFAULT now() NOT NULL,
    modified timestamptz DEFAULT now() NOT NULL
);
--;;

DO $$
BEGIN
    PERFORM tardis('dashboard');
    PERFORM install_update_modified('dashboard');
END$$;
--;;

CREATE TABLE dashboard_visualisation (
    id text DEFAULT uuid_generate_v4()::text UNIQUE NOT NULL,
    dashboard_id text REFERENCES dashboard (id) ON UPDATE CASCADE ON DELETE CASCADE,
    visualisation_id text REFERENCES visualisation (id) ON UPDATE CASCADE,
    layout jsonb,
    created timestamptz DEFAULT now() NOT NULL,
    modified timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT dashboard_visualisation_pkey PRIMARY KEY (dashboard_id, visualisation_id)
);
--;;

DO $$
BEGIN
    PERFORM tardis('dashboard_visualisation');
    PERFORM install_update_modified('dashboard_visualisation');
END$$;
--;;

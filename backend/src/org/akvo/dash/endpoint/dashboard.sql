-- :name all-dashboards :? :*
-- :doc Return all dashboards
SELECT *
FROM dashboard;

-- :name insert-dashboard :<!
-- :doc Insert dashboard.
INSERT INTO dashboard (id, title, spec)
VALUES (:id, :title, :spec::jsonb)
RETURNING *;

-- :name upsert-dashboard :<!
-- :doc Insert or update dashboard.
INSERT INTO dashboard (id, title, spec)
VALUES (:id, :title, :spec::jsonb)
ON CONFLICT (id)
DO UPDATE SET id=:id, title=:title, spec=:spec::jsonb
RETURNING *;

-- :name update-dashboard :<!
UPDATE dashboard
SET title = :title, spec = :spec::jsonb
WHERE id = :id
RETURNING *;

-- :name dashboard-by-id :? :1
-- :doc Return dashboard by id.
SELECT *
FROM dashboard
WHERE id = :id;

-- :name delete-dashboard-by-id :! :n
-- :doc Delete dashboard
DELETE FROM dashboard
WHERE id = :id;

-- :name insert-dashboard_visualisation :<!
-- :doc Add an visualisation to a dashboard
INSERT INTO dashboard_visualisation (dashboard_id, visualisation_id, layout)
VALUES (:dashboard-id, :visualisation-id, :layout::jsonb)
RETURNING *;

-- :name delete-dashboard_visualisation :! :n
-- :doc Remove all visualisations from dashboard
DELETE FROM dashboard_visualisation
WHERE dashboard_id = :dashboard-id;

-- :name dashboard_visualiation-by-dashboard-id :? :*
SELECT *
FROM dashboard_visualisation
WHERE dashboard_id = :dashboard-id;

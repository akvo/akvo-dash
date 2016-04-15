-- :name datasource-by-import :? :1
-- :doc Get import job details by import id
SELECT datasources.*
FROM imports
LEFT JOIN datasources
ON imports.datasource=datasources.id
WHERE imports.id = :id;


-- :name revision-digest-by-digest :? :1
-- :doc Get revision by digest, used to checkfor presence. Does not pull full record.
SELECT digest
FROM revisions
WHERE digest = :digest;


-- :name update-import-with-revision :! :n
-- :doc Update import with revision
UPDATE imports
SET revision = :digest, status = :status
WHERE id = :id


-- :name insert-revision :! :n
-- :doc Insert revision
INSERT INTO revisions (digest, content_type, noble, pristine)
VALUES (:digest, :content-type, :noble::jsonb, :pristine::jsonb)


-- :name update-import-status :! :n
-- :doc ...
UPDATE imports
SET status = :status
WHERE id = :id


-- :name insert-dataset :! :n
-- :doc Insert new dataset
INSERT INTO dataset(id, name) VALUES (:id, :name)


-- :name insert-dataset-version :! :n
-- :doc Insert new dataset version
INSERT INTO dataset_version(id, dataset_id, table_name)
VALUES (:id, :dataset-id, :table-name)


-- :name insert-dataset-column :! n
-- :doc Insert new dataset column
INSERT INTO dataset_column(id, dataset_id, type, c_name, c_order)
VALUES (:id, :dataset-id, :type, :c-name, :c-order)


-- :name insert-dataset-columns :! n
-- :doc Insert new dataset columns with *tuple parameters
INSERT INTO dataset_column(id, dataset_id, type, name, c_name, c_order)
VALUES :tuple*:columns

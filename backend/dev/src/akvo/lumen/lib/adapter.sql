-- :name all-merge-dsv :? :*
-- :doc all merge-dsv
select distinct on (dataset_id) id, version, dataset_id, transformations from dataset_version where transformations @>  concat ('[{"op": "core/merge-datasets"}]', '')::jsonb order by dataset_id, version desc


-- :name all-merge-dsv-bis :? :*
-- :doc all merge-dsv-bis
 select * from dataset_version where transformations @>  concat ('[{"op": "core/merge-datasets"}]', '')::jsonb



-- :name all-flow-instances :? :*
-- :doc all flow-instances
 select  data_source.spec::jsonb#>>'{"source", "instance"}' as instance  from data_source
 WHERE data_source.spec::jsonb#>>'{"source", "kind"}' = 'AKVO_FLOW'
 group by data_source.spec::jsonb#>>'{"source", "instance"}'


-- :name all-flow-datasets :? :*
-- :doc all flow-datasets
 SELECT dataset.id, data_source.spec::jsonb#>>'{"source", "kind"}' as type
 FROM dataset
 LEFT JOIN dataset_version ON dataset.id = dataset_version.dataset_id
 LEFT JOIN job_execution ON dataset_version.job_execution_id = job_execution.id
 LEFT JOIN data_source ON job_execution.data_source_id = data_source.id
  WHERE data_source.spec::jsonb#>>'{"source", "kind"}' = 'AKVO_FLOW';


-- :name all-no-flow-datasets :? :*
-- :doc all no flow-datasets
 SELECT dataset.id, data_source.spec::jsonb#>>'{"source", "kind"}' as type
 FROM dataset
 LEFT JOIN dataset_version ON dataset.id = dataset_version.dataset_id
 LEFT JOIN job_execution ON dataset_version.job_execution_id = job_execution.id
 LEFT JOIN data_source ON job_execution.data_source_id = data_source.id
  WHERE data_source.spec::jsonb#>>'{"source", "kind"}' != 'AKVO_FLOW';



-- :name db-datasets-by-id :? :1
WITH
source_data AS (
SELECT (spec->'source')::jsonb - 'refreshToken' as source
  FROM data_source, dataset_version, job_execution, dataset
 WHERE dataset_version.dataset_id = dataset.id
   AND dataset_version.version = 1
   AND dataset_version.job_execution_id = job_execution.id
   AND job_execution.data_source_id = data_source.id
   AND dataset_version.dataset_id=:id
)
SELECT dataset_version.table_name AS "table-name",
       dataset.title,
       dataset.created,
       dataset.modified,
       dataset.id,
       dataset.author,
       source_data.source,
       dataset_version.created AS "updated",
       dataset_version.columns,
       dataset_version.transformations
  FROM dataset_version, dataset, source_data
 WHERE dataset_version.dataset_id=:id
   AND dataset.id=dataset_version.dataset_id
   AND version=(SELECT max(version)
                  FROM dataset_version
                 WHERE dataset_version.dataset_id=:id);



-- :name db-all-datasets :? :*
-- :doc all datasets
  SELECT dataset.id, data_source.spec::jsonb#>>'{"source", "kind"}' as type, dataset.title, data_source.spec
  FROM dataset
  LEFT JOIN dataset_version ON dataset.id = dataset_version.dataset_id
  LEFT JOIN job_execution ON dataset_version.job_execution_id = job_execution.id
  LEFT JOIN data_source ON job_execution.data_source_id = data_source.id
  where job_execution.type='IMPORT' ;

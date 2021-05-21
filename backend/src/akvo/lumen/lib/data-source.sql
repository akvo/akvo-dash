-- :name delete-datasource-by-job-execution-id-and-status :! :n
-- :doc delete dataset
DELETE
  FROM data_source
 WHERE id IN (SELECT data_source_id
                FROM job_execution
               WHERE id = :id AND status = :status);

-- :name db-data-source-by-dataset-id :? :1
SELECT data_source.*, job_execution.id as job_execution_id
  FROM data_source, dataset_version, job_execution
 WHERE dataset_version.dataset_id = :dataset-id
   AND dataset_version.job_execution_id = job_execution.id
   AND job_execution.type = 'IMPORT'
   AND job_execution.status = 'OK'
   AND job_execution.data_source_id = data_source.id;

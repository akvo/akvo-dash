import Immutable from 'immutable';
import * as constants from '../constants/dataset';
import * as visualisationActions from './visualisation';
import { hideModal } from './activeModal';
import applyTransformation from '../reducers/transform';
import { showNotification } from './notification';
import {
  addEntityToCollection,
  addTemporaryEntitiesToCollection,
  removeTemporaryEntitiesFromCollection,
} from './collection';
import * as api from '../utilities/api';

/*
 * Fetch a dataset by id
 * fetchDataset(id)
 * Actions:
 * - FETCH_DATASET_REQUEST { id }
 * - FETCH_DATASET_SUCCESS { dataset }
 * - FETCH_DATASET_FAILURE { error, id }
 */
function fetchDatasetRequest(id) {
  return {
    type: constants.FETCH_DATASET_REQUEST,
    id,
  };
}
function fetchColumnDatasetRequest(datasetId, columnName) {
  return {
    type: constants.FETCH_COLUMN_DATASET_REQUEST,
    datasetId,
    columnName,
  };
}

function fetchDatasetSuccess(dataset) {
  return {
    type: constants.FETCH_DATASET_SUCCESS,
    dataset,
  };
}
function fetchColumnDatasetSuccess(datasetId, columnName, column) {
  return {
    type: constants.FETCH_COLUMN_DATASET_SUCCESS,
    datasetId,
    columnName,
    column,
  };
}

function fetchDatasetFailure(error, id) {
  return {
    type: constants.FETCH_DATASET_FAILURE,
    id,
  };
}

function fetchColumnDatasetFailure(error, datasetId, columnName) {
  return {
    type: constants.FETCH_COLUMN_DATASET_FAILURE,
    datasetId,
    columnName,
  };
}


export function fetchDataset(id, metaOnly, callback) {
  const suffix = metaOnly ? '/meta' : '';
  return (dispatch) => {
    dispatch(fetchDatasetRequest(id));
    return api.get(`/api/datasets/${id}${suffix}`)
      .then(({ body }) => {
        const immutableDataset = Immutable.fromJS(body);
        dispatch(fetchDatasetSuccess(immutableDataset));
        // eslint-disable-next-line no-unused-expressions
        callback && callback();
        return immutableDataset;
      })
      .catch((error) => {
        // eslint-disable-next-line no-console
        console.log(error);
        dispatch(showNotification('error', 'Failed to fetch dataset.'));
        dispatch(fetchDatasetFailure(error, id));
      });
  };
}

export function fetchDatasetGroups(id, callback) {
  return (dispatch) => {
    dispatch(fetchDatasetRequest(id));
    return api.get(`/api/datasets/${id}/groups`)
      .then(({ body }) => {
        const immutableDataset = Immutable.fromJS(body);
        dispatch(fetchDatasetSuccess(immutableDataset));
        // eslint-disable-next-line no-unused-expressions
        callback && callback();
        return immutableDataset;
      })
      .catch((error) => {
        // eslint-disable-next-line no-console
        console.log(error);
        dispatch(showNotification('error', 'Failed to fetch dataset groups.'));
        dispatch(fetchDatasetFailure(error, id));
      });
  };
}

export function fetchColumn(datasetId, columnName, type) {
  const t = type || 'text';
  return (dispatch) => {
    dispatch(fetchColumnDatasetRequest(datasetId, columnName));
    return api.get(`/api/datasets/${datasetId}/sort/${columnName}/${t}?order=value`)
      .then(({ body }) => {
        const column = body.map(o => o[1]);
        dispatch(fetchColumnDatasetSuccess(datasetId, columnName, column));
        return column;
      })
      .catch((error) => {
        // eslint-disable-next-line no-console
        console.log(error);
        dispatch(showNotification('error', 'Failed to fetch text column.'));
        dispatch(fetchColumnDatasetFailure(error, datasetId, columnName));
      });
  };
}


function fetchSortedDatasetRequest(id) {
  return {
    type: constants.FETCH_SORTED_DATASET_REQUEST,
    id,
  };
}

function fetchSortedDatasetSuccess(sortedValues) {
  return {
    type: constants.FETCH_SORTED_DATASET_SUCCESS,
    payload: sortedValues,
  };
}

function fetchSortedDatasetFailure(error, id) {
  return {
    type: constants.FETCH_SORTED_DATASET_FAILURE,
    id,
  };
}

export function fetchTextSortedDataset(id, columnName) {
  return (dispatch) => {
    dispatch(fetchSortedDatasetRequest(id));
    return api.get(`/api/datasets/${id}/sort/${columnName}/text`)
      .then(({ body }) => {
        dispatch(fetchSortedDatasetSuccess({
          id,
          columnName,
          sortedValues: body,
        }));
        return body;
      })
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to fetch dataset.'));
        dispatch(fetchSortedDatasetFailure(error, id));
      });
  };
}

export function fetchNumberSortedDataset(id, columnName) {
  return (dispatch) => {
    dispatch(fetchSortedDatasetRequest(id));
    return api.get(`/api/datasets/${id}/sort/${columnName}/number`)
      .then(({ body }) => {
        dispatch(fetchSortedDatasetSuccess({
          id,
          columnName,
          sortedValues: body,
        }));
        return body;
      })
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to fetch dataset.'));
        dispatch(fetchSortedDatasetFailure(error, id));
      });
  };
}

export function ensureDatasetFullyLoaded(id) {
  return (dispatch, getState) => {
    const { datasets } = getState().library;
    if (datasets == null || datasets[id] == null || datasets[id].get('columns') == null) {
      return dispatch(fetchDataset(id));
    }
    return Promise.resolve(datasets[id]);
  };
}

export function updateDatasetUploadStatus(status) {
  return {
    type: constants.UPDATE_UPLOAD_STATUS,
    uploadRunning: status,
  };
}

/*
 * Dataset import
 * importDataset(dataSource)
 * Actions:
 * IMPORT_DATASET_REQUEST
 * IMPORT_DATASET_SUCCESS
 * IMPORT_DATASET_FAILURE
 */

function importDatasetPending(importId, name) {
  const now = Date.now();
  return {
    type: constants.IMPORT_DATASET_PENDING,
    dataset: Immutable.fromJS({
      id: importId,
      type: 'dataset',
      status: 'PENDING',
      name,
      created: now,
      modified: now,
    }),
  };
}

function importDatasetFailure(importId, reason) {
  return {
    type: constants.IMPORT_DATASET_FAILURE,
    importId,
    reason,
    modified: Date.now(),
  };
}

function importDatasetSuccess(datasetId, importId, collectionId) {
  return (dispatch) => {
    dispatch(fetchDataset(datasetId));
    dispatch({
      type: constants.IMPORT_DATASET_SUCCESS,
      datasetId,
      importId,
    });
    if (collectionId) {
      dispatch(removeTemporaryEntitiesFromCollection(importId, collectionId));
      dispatch(addEntityToCollection('dataset', datasetId, collectionId));
    }
  };
}

function pollDatasetImportStatus(importId, name, collectionId) {
  return (dispatch) => {
    dispatch(importDatasetPending(importId, name));
    if (collectionId) {
      dispatch(addTemporaryEntitiesToCollection(importId, collectionId));
    }
    api
      .get(`/api/job_executions/dataset/${importId}`)
      .then(({ body: { status, reason, datasetId } }) => {
        if (status === 'PENDING') {
          setTimeout(
            () => dispatch(pollDatasetImportStatus(importId, name, collectionId)),
            constants.POLL_INTERVAL
          );
        } else if (status === 'FAILED') {
          dispatch(showNotification('error', 'Failed to import dataset.'));
          dispatch(importDatasetFailure(importId, reason));
        } else if (status === 'OK') {
          dispatch(importDatasetSuccess(datasetId, importId, collectionId));
        }
      })
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to import dataset.'));
        dispatch(importDatasetFailure(importId, error.message));
      });
  };
}

function importDatasetRequest(dataSource) {
  return {
    type: constants.IMPORT_DATASET_REQUEST,
    dataSource,
  };
}

export function clearImport() {
  return {
    type: constants.CLEAR_IMPORT,
  };
}

export function importDataset(dataSource, collectionId) {
  return (dispatch) => {
    dispatch(importDatasetRequest(dataSource, collectionId));
    api
      .post('/api/datasets', dataSource)
      .then(({ body: { importId } }) => {
        dispatch(pollDatasetImportStatus(importId, dataSource.name, collectionId));
        dispatch(hideModal());
        dispatch(clearImport());
      })
      .catch(() => {
        dispatch(showNotification('error', 'Failed to import dataset.'));
      });
  };
}

// Currently only name
export function saveDatasetSettings(id, { name }) {
  return {
    type: constants.SAVE_SETTINGS,
    dataset: {
      id,
      name,
    },
  };
}

// Only name for now.
export function defineDatasetSettings({ name }) {
  return {
    type: constants.DEFINE_DATASET_SETTINGS,
    dataset: { name },
  };
}

export function selectDataSource(dataSource) {
  return {
    type: constants.SELECT_DATA_SOURCE,
    dataSource,
  };
}

export function nextPage() {
  return {
    type: constants.NEXT_PAGE,
  };
}

export function previousPage() {
  return {
    type: constants.PREVIOUS_PAGE,
  };
}

export function defineDataSource(dataSource) {
  return {
    type: constants.DEFINE_DATA_SOURCE,
    dataSource,
  };
}

export function fetchDatasetsSuccess(datasets) {
  return {
    type: constants.FETCH_DATASETS_SUCCESS,
    datasets,
  };
}

/* Delete dataset actions */

function deleteDatasetRequest(id) {
  return {
    type: constants.DELETE_DATASET_REQUEST,
    id,
  };
}

/* Should only remove the dataset from the redux store.
   To delete a dataset use deleteDataset istead */
function removeDataset(id) {
  return {
    type: constants.REMOVE_DATASET,
    id,
  };
}

function deleteDatasetSuccess(id) {
  return (dispatch, getState) => {
    dispatch(removeDataset(id));
    const visualisations = getState().library.visualisations;
    Object.keys(visualisations).forEach((visualisationId) => {
      if (visualisations[visualisationId].datasetId === id) {
        dispatch(visualisationActions.removeVisualisation(visualisationId));
      }
      if (visualisations[visualisationId].visualisationType === 'map') {
        const viz = visualisations[visualisationId];

        if (viz.spec && viz.spec.layers && viz.spec.layers.some(layer => layer.datasetId === id)) {
          dispatch(visualisationActions.removeVisualisation(visualisationId));
        }
      }
    });
  };
}

function deleteDatasetFailure(id, error) {
  return {
    type: constants.DELETE_DATASET_FAILURE,
    id,
    error,
  };
}

export function deleteDataset(id) {
  return (dispatch) => {
    dispatch(deleteDatasetRequest(id));
    api
      .del(`/api/datasets/${id}`)
      .then(({ body, status }) => {
        if (status >= 400) {
          dispatch(deleteDatasetFailure(id, body));
          dispatch(showNotification('error', 'Failed to delete dataset.'));
        } else {
          dispatch(deleteDatasetSuccess(id));
        }
      })
      .catch((error) => {
        dispatch(deleteDatasetFailure(id));
        dispatch(showNotification('error', error.message || 'Failed to delete dataset.'));
      });
  };
}

function deletePendingDatasetSuccess(id) {
  return (dispatch) => {
    dispatch(removeDataset(id));
  };
}

function deletePendingDatasetFailure(id, error) {
  return {
    type: constants.DELETE_DATASET_FAILURE,
    id,
    error,
  };
}

function deleteFailedDatasetFailure(id, error) {
  return {
    type: constants.DELETE_DATASET_FAILURE,
    id,
    error,
  };
}

export function deletePendingDataset(id) {
  return (dispatch) => {
    dispatch(deleteDatasetRequest(id));
    api
      .del(`/api/data-source/job-execution/${id}/status/pending`)
      .then(() => dispatch(deletePendingDatasetSuccess(id)))
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to delete pending dataset.'));
        dispatch(deletePendingDatasetFailure(id, error));
      });
  };
}

export function deleteFailedDataset(id) {
  return (dispatch) => {
    dispatch(deleteDatasetRequest(id));
    api
      .del(`/api/data-source/job-execution/${id}/status/failed`)
      .then(() => dispatch(removeDataset(id)))
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to delete dataset.'));
        dispatch(deleteFailedDatasetFailure(id, error));
      });
  };
}


export function updateDatasetMetaRequest(id) {
  return {
    type: constants.UPDATE_DATASET_META_REQUEST,
    id,
  };
}

export function updateDatasetMetaSuccess(id, meta) {
  return {
    type: constants.UPDATE_DATASET_META_SUCCESS,
    id,
    meta,
  };
}

export function updateDatasetMetaFailure(id, error) {
  return {
    type: constants.UPDATE_DATASET_META_FAILURE,
    id,
    error,
  };
}

export function updateDatasetMeta(id, meta, callback = () => {}) {
  return (dispatch, getState) => {
    dispatch(updateDatasetMetaRequest(id));
    api
      .put(`/api/datasets/${id}`, meta)
      .then(() => {
        dispatch(updateDatasetMetaSuccess(id, meta));
        callback();
      })
      .catch((error) => {
        const title = getState().library.datasets[id].get('name');
        dispatch(showNotification('error', `Failed to update dataset: "${title}".`));
        dispatch(updateDatasetMetaFailure(id, error));
        callback(error);
      });
  };
}

/*
 * Update a dataset by dispatching `updateDataset(datasetId)`
 */

function updateDatasetTogglePending(datasetId) {
  return {
    type: constants.TOGGLE_DATASET_UPDATE_PENDING,
    id: datasetId,
  };
}

function pollDatasetUpdateStatus(updateId, datasetId, title) {
  return (dispatch) => {
    api
      .get(`/api/job_executions/dataset/${updateId}`)
      .then(({ body: { status, reason } }) => {
        if (status === 'PENDING') {
          setTimeout(
            () => dispatch(pollDatasetUpdateStatus(updateId, datasetId, title)),
            constants.POLL_INTERVAL
          );
        } else if (status === 'FAILED') {
          dispatch(updateDatasetTogglePending(datasetId));
          dispatch(showNotification('error', `Failed to update "${title}". ${reason}`));
        } else if (status === 'OK') {
          dispatch(fetchDataset(datasetId)).then(() =>
            dispatch(showNotification('info', `Successfully updated dataset "${title}"`, true))
          );
        }
      })
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to update dataset'));
        dispatch(error);
      });
  };
}

export function updateDataset(id) {
  return (dispatch, getState) => {
    const title = getState().library.datasets[id].get('name');
    dispatch(showNotification('info', `Updating "${title}"`));
    api
      .post(
        `/api/datasets/${id}/update`
      )
      .then(({ body: { updateId, error } }) => {
        if (updateId != null) {
          dispatch(updateDatasetTogglePending(id));
          dispatch(pollDatasetUpdateStatus(updateId, id, title));
        } else {
          dispatch(showNotification('error', `Update failed: ${error}`));
        }
      })
      .catch((error) => {
        dispatch(showNotification('error', error.message || 'Failed to update dataset.'));
      });
  };
}

/*
 * The reducer is run in the action creator to be able to
 * properly catch exceptions in the case of { onError: 'fail' }
 * See: https://github.com/zalmoxisus/redux-devtools-instrument/issues/5
 */
export function transform(datasetId, transformation) {
  return (dispatch, getState) => {
    const dataset = getState().library.datasets[datasetId];
    try {
      const nextDataset = applyTransformation(dataset, transformation);
      dispatch({
        type: constants.REPLACE_DATASET,
        dataset: nextDataset,
      });
    } catch (e) {
      if (transformation.get('onError') === 'fail') {
        dispatch(showNotification('error', `Transformation aborted: ${e.message}`));
      } else {
        throw e;
      }
    }
  };
}

function transformationLogRequestSent(datasetId) {
  return {
    type: constants.TRANSFORMATION_LOG_REQUEST_SENT,
    datasetId,
  };
}

function transformationSuccess(datasetId) {
  return {
    type: constants.TRANSFORMATION_SUCCESS,
    datasetId,
  };
}

function transformationFailure(datasetId, reason) {
  return {
    type: constants.TRANSFORMATION_FAILURE,
    datasetId,
    reason,
  };
}

function pollDatasetTransformationStatus(jobExecutionId, datasetId) {
  return (dispatch) => {
    api
      .get(`/api/job_executions/dataset/${jobExecutionId}`)
      .then(({ body: { status, reason } }) => {
        if (status === 'PENDING') {
          setTimeout(
            () => dispatch(pollDatasetTransformationStatus(jobExecutionId, datasetId)),
            constants.POLL_INTERVAL
          );
        } else if (status === 'FAILED') {
          dispatch(showNotification('error', reason));
          dispatch(transformationFailure(datasetId, 'Failed to transform dataset.'));
        } else if (status === 'OK') {
          dispatch(transformationSuccess(datasetId));
        }
      })
      .catch(() => {
        dispatch(showNotification('error', 'Failed to transform dataset.'));
      });
  };
}

export function sendTransformationLog(datasetId, transformations) {
  return (dispatch) => {
    dispatch(transformationLogRequestSent(datasetId));
    api
      .post(`/api/transformations/${datasetId}`, transformations.toJSON())
      .then(({ body: { jobExecutionId } }) =>
        dispatch(pollDatasetTransformationStatus(jobExecutionId, datasetId))
      )
      .catch(() => {
        dispatch(showNotification('error', 'Failed to send transformation log.'));
      });
  };
}

export function undoTransformation(id) {
  return {
    type: constants.UNDO_TRANSFORMATION,
    id,
  };
}

function txDatasetPending(jobExecutionId, name) {
  const now = Date.now();
  return {
    type: constants.TRANSFORMATION_DATASET_PENDING,
    dataset: Immutable.fromJS({
      id: jobExecutionId,
      type: 'dataset',
      status: 'PENDING',
      name,
      created: now,
      modified: now,
    }),
  };
}
function txDatasetFailure(datasetId, jobExecutionId, reason) {
  return {
    type: constants.TRANSFORMATION_FAILURE,
    jobExecutionId,
    datasetId,
    reason,
    modified: Date.now(),
  };
}
function txDatasetSuccess(datasetId, jobExecutionId) {
  return (dispatch) => {
    dispatch({
      type: constants.TRANSFORMATION_SUCCESS,
      datasetId,
      jobExecutionId,
    });
  };
}

export function startTx(datasetId) {
  return (dispatch) => {
    dispatch(showNotification('info', 'Applying transformation...'));

    dispatch({
      type: constants.TRANSFORMATION_START,
      payload: {
        datasetId,
      },
    });
  };
}

export function undoTx(datasetId) {
  return (dispatch) => {
    dispatch(showNotification('info', 'Undoing transformation...'));

    dispatch({
      type: constants.TRANSFORMATION_START,
      payload: {
        datasetId,
      },
    });
  };
}

export function endTx(datasetId, showSuccessNotif = true) {
  return (dispatch) => {
    const AUTO_HIDE = true;

    if (showSuccessNotif) {
      dispatch(showNotification('success', 'Transformation applied...', AUTO_HIDE));
    }

    dispatch({
      type: constants.TRANSFORMATION_END,
      payload: {
        datasetId,
      },
    });
  };
}

export function pollTxImportStatus(jobExecutionId, callback = () => {}, isFeatureFlag = false) {
  return (dispatch) => {
    dispatch(txDatasetPending(jobExecutionId, name));
    api
      .get(`/api/job_executions/transformation/${jobExecutionId}`)
      .then(({ body: { status, reason, datasetId } }) => {
        if (status === 'PENDING') {
          setTimeout(
            () => dispatch(pollTxImportStatus(jobExecutionId, callback, isFeatureFlag)),
            constants.POLL_INTERVAL
          );
        } else if (status === 'FAILED') {
          dispatch(showNotification('error', reason));
          dispatch(txDatasetFailure(datasetId, jobExecutionId, reason));
          const DONT_SHOW_SUCCESS_NOTIF = false;
          dispatch(endTx(datasetId, DONT_SHOW_SUCCESS_NOTIF));
        } else if (status === 'OK') {
          dispatch(txDatasetSuccess(datasetId, jobExecutionId));
          callback();
        }
      })
      .catch((error) => {
        dispatch(showNotification('error', 'Failed to transform dataset.'));
        dispatch(txDatasetFailure(jobExecutionId, error.message));
      });
  };
}

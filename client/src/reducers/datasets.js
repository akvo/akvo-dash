import * as constants from '../constants/dataset';
import update from 'react-addons-update';
import applyTransformation from './transform';

export const initialState = {};

function createDataset(state, dataset) {
  const id = dataset.id;
  return Object.assign({}, state, {
    [id]: dataset,
  });
}

function saveDatasetSettings(state, dataset) {
  const { id, name } = dataset;
  return Object.assign({}, state, {
    [id]: Object.assign({}, state[id], {
      name,
    }),
  });
}

function importDatasetPending(state, dataset) {
  const id = dataset.id;
  return Object.assign({}, state, {
    [id]: dataset,
  });
}

function importDatasetFailure(state, { importId, reason, modified }) {
  const dataset = Object.assign({}, state[importId], {
    status: 'FAILED',
    reason,
    modified,
  });
  return Object.assign({}, state, {
    [importId]: dataset,
  });
}

function importDatasetSuccess(state, { importId }) {
  const newState = Object.assign({}, state);
  delete newState[importId];
  return newState;
}

function saveDataset(state, dataset) {
  const id = dataset.id;
  const ds = update(dataset, { $merge: { type: 'dataset' } });
  return update(state, {
    [id]: { $set: ds },
  });
}

function saveDatasets(state, ds) {
  // TODO we should probably not overwrite?
  return ds.reduce((result, dataset) => {
    const id = dataset.id;
    return Object.assign({}, result, {
      [id]: Object.assign({}, dataset, { type: 'dataset' }),
    });
  }, state);
}

function removeDataset(state, id) {
  const newState = Object.assign({}, state);
  delete newState[id];
  return newState;
}

function transformDataset(state, { datasetId, transformation }) {
  const newState = Object.assign({}, state, {
    [datasetId]: applyTransformation(state[datasetId], transformation),
  });
  return newState;
}

function transformationLogRequestSent(state, { datasetId }) {
  const newDataset = Object.assign({}, state[datasetId], { status: 'PENDING' });
  // Remove history, we don't need it anymore
  delete newDataset.history;
  return Object.assign({}, state, {
    [datasetId]: newDataset,
  });
}

function transformationFailure(state, { datasetId, reason }) {
  return Object.assign({}, state, {
    [datasetId]: Object.assign({}, state[datasetId], {
      status: 'FAILED',
      reason,
    }),
  });
}

function transformationSuccess(state, { datasetId }) {
  return Object.assign({}, state, {
    [datasetId]: Object.assign({}, state[datasetId], {
      status: 'OK',
    }),
  });
}

export default function datasets(state = initialState, action) {
  switch (action.type) {
    case constants.CREATE:
      return createDataset(state, action.dataset);
    case constants.SAVE_SETTINGS:
      return saveDatasetSettings(state, action.dataset);
    case constants.IMPORT_DATASET_PENDING:
      return importDatasetPending(state, action.dataset);
    case constants.IMPORT_DATASET_FAILURE:
      return importDatasetFailure(state, action);
    case constants.IMPORT_DATASET_SUCCESS:
      return importDatasetSuccess(state, action);
    case constants.FETCH_DATASET_SUCCESS:
      return saveDataset(state, action.dataset);
    case constants.FETCH_DATASETS_SUCCESS:
      return saveDatasets(state, action.datasets);
    case constants.REMOVE_DATASET:
      return removeDataset(state, action.id);
    case constants.TRANSFORM_DATASET:
      return transformDataset(state, action);
    case constants.TRANSFORMATION_LOG_REQUEST_SENT:
      return transformationLogRequestSent(state, action);
    case constants.TRANSFORMATION_SUCCESS:
      return transformationSuccess(state, action);
    case constants.TRANSFORMATION_FAILURE:
      return transformationFailure(state, action);
    default: return state;
  }
}

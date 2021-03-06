import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';
import { FormattedMessage } from 'react-intl';
import update from 'immutability-helper';
import LibraryHeader from './library/LibraryHeader';
import LibraryListing from './library/LibraryListing';
import CheckboxEntityMenu from './library/CheckboxEntityMenu';
import DeleteConfirmationModal from './modals/DeleteConfirmationModal';
import { showModal } from '../actions/activeModal';
import { fetchLibrary } from '../actions/library';
import { deleteVisualisation } from '../actions/visualisation';
import { deleteDataset, deleteFailedDataset, deletePendingDataset, updateDataset } from '../actions/dataset';
import { deleteDashboard } from '../actions/dashboard';
import { deleteRaster, deletePendingRaster } from '../actions/raster';
import { addEntitiesToCollection, removeEntitiesFromCollection } from '../actions/collection';
import { isSelectionFilled, collectionModel, filterLibraryByCollection } from '../utilities/collection';
import * as entity from '../domain/entity';
import { trackPageView } from '../utilities/analytics';

require('./Library.scss');

function mergeQuery(location, query) {
  const q = new URLSearchParams(query).toString();
  return Object.assign({}, location, {
    search: `?${q}`,
  });
}

function updateQueryAction(history, location, query) {
  const x = mergeQuery(location, query);
  history.push(x);
  return x;
}

class Library extends Component {
  constructor() {
    super();
    this.state = {
      pendingDeleteEntity: null,
      collection: null,
      checkboxEntities: collectionModel(),
    };

    this.handleCheckEntity = this.handleCheckEntity.bind(this);
    this.handleEntityAction = this.handleEntityAction.bind(this);
    this.handleDeleteEntity = this.handleDeleteEntity.bind(this);
    this.handleCreateCollection = this.handleCreateCollection.bind(this);
    this.handleRemoveEntitiesFromCollection = this.handleRemoveEntitiesFromCollection.bind(this);
    this.handleAddEntitiesToCollection = this.handleAddEntitiesToCollection.bind(this);
  }

  componentDidMount() {
    const { dispatch, history } = this.props;
    const redirect = window.localStorage.getItem('redirect');
    if (redirect) {
      window.localStorage.removeItem('redirect');
      history.push(redirect);
    } else {
      dispatch(fetchLibrary());
      trackPageView('Library');
    }
  }

  componentDidUpdate(prevProps, prevState) {
    if (this.props.collections) {
      const collectionId = this.props.params.collectionId;
      const collection = collectionId ? this.props.collections[collectionId] : null;
      if (!collection && prevState.collection) {
        this.props.history.push('/library');
      }
      if (collectionId && !collection) {
        this.props.history.push('/library');
      }
    }
  }

  static getDerivedStateFromProps(nextProps, prevState) {
    if (nextProps.collections) {
      const collectionId = nextProps.params.collectionId;
      const collection = collectionId ? nextProps.collections[collectionId] : null;
      const newState = {};
      if (collection) {
        if (collection !== prevState.collection) {
          newState.collection = Object.assign({}, collection);
        }
      } else if (prevState.collection) {
        newState.collection = null;
      }
      return newState;
    }
    return null;
  }

  handleCheckEntity(entityId, entityType) {
    const e = { entityId, entityType };
    let newCheckboxEntities = this.state.checkboxEntities[`${entityType}s`].slice(0);
    if (newCheckboxEntities.find(o => o === e.entityId) !== undefined) {
      newCheckboxEntities = newCheckboxEntities.filter(o => o !== e.entityId);
      this.setState({ checkboxEntities: update(this.state.checkboxEntities, { [`${entityType}s`]: { $set: newCheckboxEntities } }) });
    } else {
      this.setState({ checkboxEntities: update(this.state.checkboxEntities, { [`${entityType}s`]: { $push: [e.entityId] } }) });
    }
  }

  handleDeleteEntity(entityType, id) {
    const { dispatch, datasets, rasters } = this.props;
    switch (entityType) {
      case 'dataset':
        if (entity.isPending(datasets[id])) {
          dispatch(deletePendingDataset(id));
        } else if (entity.isFailed(datasets[id])) {
          dispatch(deleteFailedDataset(id));
        } else {
          dispatch(deleteDataset(id));
        }
        break;
      case 'visualisation':
        dispatch(deleteVisualisation(id));
        break;
      case 'dashboard':
        dispatch(deleteDashboard(id));
        break;
      case 'raster':
        if (!entity.isPending(rasters[id])) {
          dispatch(deleteRaster(id));
        } else {
          dispatch(deletePendingRaster(id));
        }
        break;
      default:
        throw new Error(`Invalid entity type: ${entityType}`);
    }
  }

  handleUpdateDataset(id) {
    const { dispatch } = this.props;
    dispatch(updateDataset(id));
  }

  handleCreateCollection(entities) {
    if (entities) {
      return this.props.dispatch(showModal('create-collection', { entities }));
    }
    return this.props.dispatch(showModal('create-collection'));
  }

  handleDeleteCollection(collectionId) {
    return this.props.dispatch(
      showModal('delete-collection', { collection: this.props.collections[collectionId] })
    );
  }

  handleAddEntitiesToCollection(entities, collectionId) {
    this.props.dispatch(addEntitiesToCollection(entities, collectionId));
  }

  handleRemoveEntitiesFromCollection(entities, collectionId) {
    this.props.dispatch(removeEntitiesFromCollection(entities, collectionId));
  }

  handleEntityAction(actionType, entityType, entityId) {
    if (actionType === 'delete') {
      this.setState({ pendingDeleteEntity: { entityType, entityId } });
    } else if (actionType === 'update-dataset') {
      this.handleUpdateDataset(entityId);
    } else if (actionType === 'add-to-collection:new') {
      if (!this.state.collection) {
        this.handleCreateCollection(update(this.state.checkboxEntities, { [`${entityType}s`]: { $push: [entityId] } }));
      }
    } else if (actionType.indexOf('add-to-collection:') > -1) {
      if (!this.state.collection) {
        const collectionId = actionType.replace('add-to-collection:', '');
        const m = collectionModel();
        m[`${entityType}s`].push(entityId);
        this.handleAddEntitiesToCollection(m, collectionId);
      }
    } else if (actionType.indexOf('remove-from-collection:') > -1) {
      const collectionId = actionType.replace('remove-from-collection:', '');
      const m = collectionModel();
      m[`${entityType}s`].push(entityId);
      this.handleRemoveEntitiesFromCollection(m, collectionId);
    } else {
      throw new Error(`Action ${actionType} not yet implemented for entity type ${entityType}`);
    }
  }

  render() {
    const { dispatch, location, datasets, visualisations, dashboards, rasters,
            filteredDashboard, history } = this.props;

    const collections = this.props.collections ? this.props.collections : {};
    const { pendingDeleteEntity, collection } = this.state;
    const q = new URLSearchParams(location.search);
    const displayMode = q.get('display') || 'grid';
    const sortOrder = q.get('sort') || 'last_modified';
    const isReverseSort = q.get('reverse') === 'true';
    const filterBy = q.get('filter') || 'all';
    const searchString = q.get('search') || '';

    return (
      <div className="Library" data-test-id="library">

        {this.state.pendingDeleteEntity && (
          <DeleteConfirmationModal
            isOpen
            filteredDashboard={filteredDashboard}
            entityId={pendingDeleteEntity.entityId}
            entityType={pendingDeleteEntity.entityType}
            library={{ datasets, visualisations, dashboards, rasters }}
            onCancel={() => this.setState({ pendingDeleteEntity: null })}
            onDelete={() => {
              this.setState({ pendingDeleteEntity: null });
              this.handleDeleteEntity(pendingDeleteEntity.entityType, pendingDeleteEntity.entityId);
            }}
          />
        )}

        <LibraryHeader
          location={collection ? collection.title : <FormattedMessage id="library" />}
          onCreateCollection={this.handleCreateCollection}
          onAddEntitiesToCollection={this.handleAddEntitiesToCollection}
          onRemoveEntitiesFromCollection={this.handleRemoveEntitiesFromCollection}
          displayMode={displayMode}
          onChangeDisplayMode={newDisplayMode =>
                               updateQueryAction(history, location, {
                                 display: newDisplayMode,
                               })
                              }
          sortOrder={sortOrder}
          onChangeSortOrder={newSortOrder =>
                             updateQueryAction(history, location, {
                               sort: newSortOrder,
                             })
                            }
          isReverseSort={isReverseSort}
          onChangeReverseSort={newReverseSort =>
                               updateQueryAction(history, location, {
                                 reverse: newReverseSort,
                               })
                              }
          filterBy={filterBy}
          onChangeFilterBy={newFilterBy =>
                            updateQueryAction(history, location, {
                              filter: newFilterBy,
                            })
                           }
          searchString={searchString}
          onSetSearchString={newSearchString =>
                             updateQueryAction(history, location, {
                               search: newSearchString,
                             })}
          onCreate={(type) => {
            const { params } = this.props;
            const meta = { collectionId: params.collectionId, from: 'library' };
            if (type === 'dataset') {
              // Data set creation is handled in a modal
              dispatch(showModal('create-dataset', meta));
            } else if (type === 'collection') {
              dispatch(showModal('create-collection'));
            } else {
              const x = { pathname: `/${type}//create`, state: meta };
              history.push(x);
            }
          }}
        />

        <LibraryListing
          displayMode={displayMode}
          sortOrder={sortOrder}
          isReverseSort={isReverseSort}
          filterBy={filterBy}
          searchString={searchString}
          collections={collections}
          currentCollection={collection}
          library={collection ? filterLibraryByCollection(this.props, collection) : this.props}
          checkboxEntities={this.state.checkboxEntities}
          onCheckEntity={this.handleCheckEntity}
          onEntityAction={this.handleEntityAction}
        />

        {this.props.children}

        {isSelectionFilled(this.state.checkboxEntities) && (
          <CheckboxEntityMenu
            collections={collections}
            collection={collection}
            onCreateCollection={this.handleCreateCollection}
            onAddEntitiesToCollection={this.handleAddEntitiesToCollection}
            onRemoveEntitiesFromCollection={this.handleRemoveEntitiesFromCollection}
            checkboxEntities={this.state.checkboxEntities}
            onDeselectEntities={() => this.setState({ checkboxEntities: collectionModel() })}
          />
        )}
      </div>
    );
  }
}

Library.propTypes = {
  dispatch: PropTypes.func,
  location: PropTypes.object,
  params: PropTypes.object.isRequired,
  history: PropTypes.object.isRequired,
  children: PropTypes.element,
  datasets: PropTypes.object.isRequired,
  visualisations: PropTypes.object.isRequired,
  dashboards: PropTypes.object.isRequired,
  rasters: PropTypes.object.isRequired,
  collections: PropTypes.object,
  filteredDashboard: PropTypes.bool,
};

export default connect(state => state.library)(withRouter(Library));

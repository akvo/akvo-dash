import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import { routeActions } from 'react-router-redux';
import LibraryHeader from './library/LibraryHeader';
import LibraryListing from './library/LibraryListing';
import { showModal } from '../actions/activeModal';

require('../styles/Library.scss');

function mergeQuery(location, query) {
  return Object.assign({}, location, {
    query: Object.assign({}, location.query, query),
  });
}

function updateQueryAction(location, query) {
  return routeActions.push(mergeQuery(location, query));
}

class Library extends Component {

  constructor() {
    super();
    this.handleSelectEntity = this.handleSelectEntity.bind(this);
  }

  componentDidMount() {
    // Fetch the library.
  }


  handleSelectEntity(entityType, id) {
    this.props.dispatch(routeActions.push(`/${entityType}/${id}`));
  }

  render() {
    const { dispatch, location, params } = this.props;
    const query = location.query;
    const displayMode = query.display || 'list';
    const sortOrder = query.sort || 'last_modified';
    const isReverseSort = query.reverse === 'true';
    const filterBy = query.filter || 'all';
    const searchString = query.search || '';
    const collection = params.collection || null;
    return (
      <div className="Library">
        <LibraryHeader
          displayMode={displayMode}
          onChangeDisplayMode={(newDisplayMode) => {
            dispatch(updateQueryAction(location, {
              display: newDisplayMode,
            }));
          }}
          sortOrder={sortOrder}
          onChangeSortOrder={(newSortOrder) => {
            dispatch(updateQueryAction(location, {
              sort: newSortOrder,
            }));
          }}
          isReverseSort={isReverseSort}
          onChangeReverseSort={(newReverseSort) => {
            dispatch(updateQueryAction(location, {
              reverse: newReverseSort,
            }));
          }}
          filterBy={filterBy}
          onChangeFilterBy={(newFilterBy) => {
            dispatch(updateQueryAction(location, {
              filter: newFilterBy,
            }));
          }}
          searchString={searchString}
          onSetSearchString={(newSearchString) => {
            if (newSearchString !== '') {
              dispatch(updateQueryAction(location, {
                search: newSearchString,
              }));
            }
          }}
          onCreate={(type) => {
            if (type === 'dataset') {
              // Data set creation is handled in a modal
              dispatch(showModal('create-dataset'));
            } else {
              dispatch(routeActions.push(`/${type}/create`));
            }
          }}/>
        <LibraryListing
          displayMode={displayMode}
          sortOrder={sortOrder}
          isReverseSort={isReverseSort}
          filterBy={filterBy}
          searchString={searchString}
          collection={collection}
          library={this.props}
          onSelectEntity={this.handleSelectEntity}/>
        {this.props.children}
      </div>
    );
  }
}

Library.propTypes = {
  dispatch: PropTypes.func,
  location: PropTypes.object,
  params: PropTypes.object,
  children: PropTypes.element,
};

export default connect(state => state.library)(Library);

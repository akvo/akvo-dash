import React, { Component, PropTypes } from 'react';

import LocationIndicator from './LocationIndicator';
import LibraryCreateButton from './LibraryCreateButton';
import LibrarySearch from './LibrarySearch';
import LibraryTabList from './LibraryTabList';
import LibraryDisplayMenu from './LibraryDisplayMenu';

require('../../styles/LibraryHeader.scss');

export default class LibraryHeader extends Component {
  render() {
    return (
      <div className="LibraryHeader">
        <LocationIndicator />
        <LibraryCreateButton
          onCreate={this.props.onCreate}/>
        <LibrarySearch
          onSearch={this.props.onSetSearchString}/>
        <LibraryTabList
          selected={this.props.filterBy}
          onSelect={this.props.onChangeFilterBy}/>
        <LibraryDisplayMenu
          sortOrder={this.props.sortOrder}
          onChangeSortOrder={this.props.onChangeSortOrder}
          isReverseSort={this.props.isReverseSort}
          onChangeReverseSort={this.props.onChangeReverseSort}
          displayMode={this.props.displayMode}
          onChangeDisplayMode={this.props.onChangeDisplayMode}/>
      </div>
    );
  }
}

LibraryHeader.propTypes = {
  displayMode: PropTypes.string.isRequired,
  onChangeDisplayMode: PropTypes.func.isRequired,
  sortOrder: PropTypes.string.isRequired,
  onChangeSortOrder: PropTypes.func.isRequired,
  isReverseSort: PropTypes.bool.isRequired,
  onChangeReverseSort: PropTypes.func.isRequired,
  filterBy: PropTypes.string.isRequired,
  onChangeFilterBy: PropTypes.func.isRequired,
  onSetSearchString: PropTypes.func.isRequired,
  onCreate: PropTypes.func.isRequired,
};

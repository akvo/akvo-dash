import { merge, cloneDeep } from 'lodash';
import React, { Component } from 'react';
import PropTypes from 'prop-types';
import Immutable from 'immutable';
import { FormattedMessage } from 'react-intl';
import SelectMenu from '../../common/SelectMenu';
import ToggleInput from '../../common/ToggleInput';
import SidebarHeader from './SidebarHeader';
import SidebarControls from './SidebarControls';
import * as API from '../../../api';

function textColumnOptions(columns) {
  return columns
    .filter(column => column.get('type') === 'multiple')
    .map(column => ({
      label: column.get('title'),
      value: column.get('columnName'),
    }))
    .toJS();
}

function filterByMultipleAndColumnName(columns, columnName) {
  return columns
    .filter(column => column.get('type') === 'multiple' && column.get('columnName') === columnName)
    .toJS()[0];
}

function SelectColumn({ columns, idx, onChange, value }) {
  return (
    <div className="inputGroup">
      <label htmlFor="columnName">
        <FormattedMessage id="select_n_column" values={{ idx }} />
      </label>
      <SelectMenu
        name="columnName"
        value={value}
        onChange={onChange}
        options={textColumnOptions(columns)}
      />
    </div>
  );
}

SelectColumn.propTypes = {
  columns: PropTypes.object.isRequired,
  idx: PropTypes.number.isRequired,
  onChange: PropTypes.func.isRequired,
  value: PropTypes.string,
};

function MultipleColumnImage(props) {
  const { hasImage, extractImage, onExtractImage } = props;
  if (hasImage) {
    return (
      <ToggleInput
        name="image"
        type="checkbox"
        labelId="extract_image_question"
        className="showLegend"
        checked={extractImage}
        onChange={onExtractImage}
      />
    );
  }
  return null;
}

MultipleColumnImage.propTypes = {
  hasImage: PropTypes.bool.isRequired,
  extractImage: PropTypes.func.isRequired,
  onExtractImage: PropTypes.object.isRequired,
};

class Column extends Component {
  constructor(props) {
    super(props);
    this.props.onExtractColumn(false);
    this.onColumnName = this.onColumnName.bind(this);
  }

  onColumnName(evt) {
    this.props.onColumnName(evt.target.value);
  }
  render() {
    const { api, ui } = this.props;
    return (
      <div className="inputs">
        <div className="inputGroup">
          <div>
            <FormattedMessage id="extract_column_question" />
            {ui.extract ? (
               null
            ) : (
              <div>
                {' '}
                {api.name} :: {api.type}
              </div>
            )}
            <ToggleInput
              name="extractColumn"
              type="checkbox"
              className="showLegend"
              checked={ui.extract}
              onChange={this.props.onExtractColumn}
            />
          </div>
          {ui.extract ? (
            <input
              value={ui.name}
              placeholder={api.name} // TODO: how to i18n this value?
              type="text"
              className="titleTextInput"
              onChange={this.onColumnName}
              data-test-id={`column-title-${api.id}`}
            />
          ) : (
             null
          )}
        </div>
      </div>
    );
  }
}
Column.propTypes = {
  onExtractColumn: PropTypes.func.isRequired,
  onColumnName: PropTypes.func.isRequired,
  api: PropTypes.object.isRequired,
  ui: PropTypes.object.isRequired,
};

function MultipleColumnList(props) {
  const { onColumnName, onExtractColumn } = props;
  const columns = props.api.columns || [];
  const columList = columns.map((column, index) => (
    <Column
      key={column.id}
      api={column}
      ui={props.ui.columns[index]}
      idx={index}
      onColumnName={onColumnName(index)}
      onExtractColumn={onExtractColumn(index)}
    />
  ));
  return <div>{columList}</div>;
}

MultipleColumnList.propTypes = {
  onExtractColumn: PropTypes.func.isRequired,
  onColumnName: PropTypes.func.isRequired,
  api: PropTypes.object.isRequired,
  ui: PropTypes.object.isRequired,
};

function MultipleColumn(props) {
  const {
    api,
    ui,
    onExtractImage,
    onColumnName,
    onExtractColumn,
  } = props;
  return api ? (
    <div>
      <MultipleColumnImage
        hasImage={api.hasImage}
        extractImage={ui.extractImage}
        onExtractImage={onExtractImage}
      />
      <MultipleColumnList
        api={api}
        ui={ui}
        onColumnName={onColumnName}
        onExtractColumn={onExtractColumn}
      />
    </div>
  ) : null;
}

MultipleColumn.propTypes = {
  api: PropTypes.object.isRequired,
  ui: PropTypes.object.isRequired,
  onExtractImage: PropTypes.func.isRequired,
  onColumnName: PropTypes.func.isRequired,
  onExtractColumn: PropTypes.func.isRequired,
};

function apiMultipleColumn(column, callback) {
  API
    .get('/api/multiple-column', {
      query: JSON.stringify({
        multipleType: column.multipleType,
        multipleId: column.multipleId,
      }),
    })
    .then(response => response.json())
    .then(callback);
}
export default class ExtractMultiple extends Component {
  constructor() {
    super();
    this.state = {
      transformation: Immutable.fromJS({
        op: 'core/extract-multiple',
        args: {},
        onError: 'fail',
      }),

      extractMultiple: {
        api: null,
        ui: { extractImage: null, columns: [], selectedColumn: { name: null } },
      },
    };
    this.onExtractImage = this.onExtractImage.bind(this);
    this.onColumnName = this.onColumnName.bind(this);
    this.onExtractColumn = this.onExtractColumn.bind(this);
  }

  onExtractColumn(idx) {
    return (extractColumn) => {
      if (!extractColumn) {
        this.onColumnName(idx)(this.state.extractMultiple.api.columns[idx].name);
      }
      const extractMultiple = merge(this.state.extractMultiple, {
        ui: {
          columns: this.state.extractMultiple.ui.columns.map(
            (column, index) => {
              const columnBis = column;
              if (idx === index) {
                columnBis.extract = extractColumn;
              }
              return columnBis;
            }
          ),
        },
      });
      this.setState({
        extractMultiple,
        transformation: this.state.transformation.setIn(
          ['args'],
          extractMultiple.ui
        ),
      });
    };
  }

  onSelectColumn(columns, columnName) {
    const column = filterByMultipleAndColumnName(columns, columnName);
    apiMultipleColumn(column, (apiRes) => {
      const apiResBis = apiRes;
      apiResBis.columnName = columnName;
      const ui = cloneDeep(apiResBis); // cloning object
      delete ui.hasImage;
      ui.extractImage = false;
      ui.selectedColumn = column;

      this.setState({
        extractMultiple: {
          api: apiRes,
          ui,
        },
        transformation: this.state.transformation.setIn(['args'], ui),
      });
    });
  }

  onExtractImage(value) {
    const extractMultiple = merge(this.state.extractMultiple, {
      ui: { extractImage: value },
    });
    this.setState({
      extractMultiple,
      transformation: this.state.transformation.setIn(
        ['args'],
        extractMultiple.ui
      ),
    });
  }

  onColumnName(idx) {
    return (columnName) => {
      const extractMultiple = merge(this.state.extractMultiple, {
        ui: {
          columns: this.state.extractMultiple.ui.columns.map((column, index) => {
            const columnBis = column;
            if (idx === index) {
              columnBis.name = columnName;
            }
            return columnBis;
          }),
        },
      });
      this.setState({
        extractMultiple,
        transformation: this.state.transformation.setIn(
          ['args'],
          extractMultiple.ui
        ),
      });
    };
  }

  isValidTransformation() {
    const {
      extractMultiple: {
        ui: { extractImage, columns, selectedColumn },
      },
    } = this.state;
    const extractColumns =
      columns.map(c => c.extract).filter(e => e).length !== 0;
    return selectedColumn && (extractImage || extractColumns);
  }

  render() {
    const { onClose, onApply, columns } = this.props;
    const { extractMultiple: { ui: { selectedColumn } } } = this.state;
    return (
      <div className="DataTableSidebar">
        <SidebarHeader onClose={onClose}>
          <FormattedMessage id="extract_multiple" />
        </SidebarHeader>
        <div className="inputs">
          <SelectColumn
            columns={columns}
            idx={1}
            onChange={columnName => this.onSelectColumn(columns, columnName)}
            value={selectedColumn.columnName}
          />
          <MultipleColumn
            api={this.state.extractMultiple.api}
            ui={this.state.extractMultiple.ui}
            selectedColumn={this.state.selectedColumn}
            onExtractImage={this.onExtractImage}
            extractImage={this.state.extractMultiple.ui.extractImage}
            onColumnName={this.onColumnName}
            onExtractColumn={this.onExtractColumn}
          />
        </div>

        <SidebarControls
          positiveButtonText={<FormattedMessage id="extract" />}
          onApply={
            this.isValidTransformation()
              ? () => onApply(this.state.transformation)
              : () => {}
          }
          onClose={onClose}
        />
      </div>
    );
  }
}

ExtractMultiple.propTypes = {
  onClose: PropTypes.func.isRequired,
  onApply: PropTypes.func.isRequired,
  columns: PropTypes.object.isRequired,
};

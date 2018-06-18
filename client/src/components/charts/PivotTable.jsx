import React from 'react';
import PropTypes from 'prop-types';
import { FormattedMessage } from 'react-intl';
import moment from 'moment';
import get from 'lodash/get';
import { replaceLabelIfValueEmpty, processPivotData } from '../../utilities/chart';

require('./PivotTable.scss');

const meanPixelsPerChar = 7.5; // Used for calculating min-widths for columns
const defaultCategoryWidth = 100; // Number of pixels to wrap category columns at
const columnLimit = 50; // Don't render a table if there are more columns than this

const formatTitle = (title) => {
  const maxTitleLength = 64;
  if (!title) return title;
  if (title.toString().length <= maxTitleLength) return title;

  return `${title.toString().substring(0, maxTitleLength - 1)}…`;
};

const getColumnHeaderClassname = (cell, index, spec) => {
  if (index === 0) {
    if (spec.rowColumn !== null) {
      return 'rowColumnTitle';
    } else if (spec.categoryColumn !== null) {
      return 'spacer';
    }
    return '';
  }
  return 'uniqueColumnValue';
};

const getColumnHeaderBody = (cell, index, spec) => {
  if (index > 0) {
    return formatTitle(replaceLabelIfValueEmpty(cell.title));
  }

  return formatTitle(spec.rowTitle ? spec.rowTitle : cell.title);
};


/* Returns the min column width that will limit wrapping to two lines.
/* This is not currently possible with a stylesheet-only approach. */
const getMinRowTitleWidth = text =>
  Math.min(
    Math.ceil(((text != null ? text.toString().length : 0) * meanPixelsPerChar) / 2),
    32 * meanPixelsPerChar
  );

const getMinCategoryTitleWidth = text =>
  Math.min(
    (text != null ? text.toString().length : 0) * meanPixelsPerChar,
    defaultCategoryWidth
  );

const formatCell = (index, cell, spec, columns) => {
  if (spec.valueDisplay != null && spec.valueDisplay !== 'default') {
    // Cell value has already been formatted, so just display as-is
    return cell;
  }

  const type = columns[index].type;

  if (type === 'number') {
    if (!spec.decimalPlaces) {
      return Math.round(cell);
    }
    // eslint-disable-next-line no-restricted-properties
    return Math.round(cell * Math.pow(10, spec.decimalPlaces)) / Math.pow(10, spec.decimalPlaces);
  }

  return cell;
};

export default function PivotTable({ width, height, visualisation, context, datasets }) {
  const { spec } = visualisation;
  const data = processPivotData(visualisation.data, spec);
  const tooManyColumns = data && data.columns && data.columns.length >= columnLimit;
  let totalsClass = data && data.metadata &&
    data.metadata.hasRowTotals && data.metadata.hasColumnTotals ?
    'hasTotals' : '';
  if (spec.hideRowTotals) {
    totalsClass = `${totalsClass} hideRowTotals`;
  }
  if (spec.hideColumnTotals) {
    totalsClass = `${totalsClass} hideColumnTotals`;
  }

  if (!data) {
    return (
      <div
        className="PivotTable dashChart"
        style={{
          width,
          height,
        }}
      >
        Please choose a dataset.
      </div>
    );
  }

  if (tooManyColumns) {
    return (
      <div
        className="PivotTable dashChart"
        style={{
          width,
          height,
        }}
      >
        <p>
          There are {data.columns.length} columns in this table, which is too many to display.
          {context === 'editor' &&
            <span>
              {' '}Please choose a different column, or use a dataset filter to reduce the number of unique values.
            </span>
          }
        </p>
      </div>
    );
  }

  return (
    <div
      className={`PivotTable dashChart ${totalsClass}`}
      style={{
        width,
        height,
      }}
    >
      <table>
        <thead>
          <tr className="title">
            <th colSpan={get(data, 'columns.length')}>
              <span>
                {visualisation.name}
              </span>
              <br />
              <p className="chartMeta">
                <span className="capitalize">
                  <FormattedMessage id="data_last_updated" />
                </span>: {moment(datasets[visualisation.datasetId].get('updated')).format('Do MMM YYYY - HH:mm')}
              </p>
            </th>
          </tr>
          {get(data, 'metadata.categoryColumnTitle') &&
            <tr>
              <th className="spacer" />
              <th
                colSpan={get(data, 'columns.length') - 1}
                className="categoryColumnTitle"
              >
                <span>
                  {spec.categoryTitle ?
                    spec.categoryTitle : data.metadata.categoryColumnTitle}
                </span>
              </th>
            </tr>
          }
          <tr className="columnHeader">
            {data.columns.map((cell, index) =>
              <th
                key={index}
                className={getColumnHeaderClassname(cell, index, visualisation.spec)}
                title={index === 0 ? cell.title : replaceLabelIfValueEmpty(cell.title)}
                style={{
                  minWidth: index === 0 ?
                    getMinRowTitleWidth(getColumnHeaderBody(cell, index, spec))
                    :
                    getMinCategoryTitleWidth(getColumnHeaderBody(cell, index, spec))
                    ,
                }}
              >
                <span>
                  {getColumnHeaderBody(cell, index, spec)}
                </span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {data.rows.map((row, rowIndex) =>
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) =>
                <td
                  key={cellIndex}
                  className={cellIndex === 0 ? 'uniqueRowValue' : 'cell'}
                  // Only set the title  attribute if the index is 0
                  {...cellIndex === 0 ?
                    {
                      title: replaceLabelIfValueEmpty(cell),
                    } : {}
                  }
                >
                  <span>
                    {cellIndex === 0 ?
                      <span
                        style={{
                          minWidth: getMinRowTitleWidth(cell ? cell.toString() : ''),
                        }}
                      >
                        {formatTitle(replaceLabelIfValueEmpty(cell))}
                      </span>
                      :
                      formatCell(cellIndex, cell, visualisation.spec, data.columns)
                    }
                  </span>
                </td>
              )}
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

PivotTable.propTypes = {
  visualisation: PropTypes.object.isRequired,
  datasets: PropTypes.object.isRequired,
  width: PropTypes.number,
  height: PropTypes.number,
  context: PropTypes.string,
};

import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { FormattedMessage } from 'react-intl';

import AsyncVisualisationViewer from '../charts/AsyncVisualisationViewer';
import { getTitle, getDataLastUpdated } from '../../utilities/chart';

require('./DashboardViewerItem.scss');

const cMargin = 10; // margin between containers (in px)
const cPadding = 20; // padding inside container (in px)
const TITLE_HEIGHT = 60;

export default class DashboardViewerItem extends Component {
  constructor() {
    super();

    this.getItemStyle = this.getItemStyle.bind(this);
  }
  getItemStyle() {
    const { item, layout, canvasWidth, viewportType } = this.props;
    const numCols = 12;
    const unit = canvasWidth / numCols;
    const MIN_HEIGHT = 300;

    switch (viewportType) {
      case 'small':
        return {
          display: 'inline-block',
          width: canvasWidth - (cMargin * 2),
          minHeight: MIN_HEIGHT,
          height: (
            item.type === 'visualisation' &&
            item.visualisation &&
            item.visualisation.visualisationType === 'map'
          ) ?
            MIN_HEIGHT :
            null,
          margin: cMargin,
          padding: cPadding,
        };

      case 'medium': {
        return {
          display: 'inline-block',
          width: (canvasWidth / 2) - (cMargin * 2),
          minHeight: MIN_HEIGHT,
          height: (
            item.type === 'visualisation' &&
            item.visualisation &&
            item.visualisation.visualisationType === 'map'
          ) ?
            MIN_HEIGHT :
            null,
          margin: cMargin,
          padding: cPadding,
        };
      }

      case 'large':
        return {
          position: 'absolute',
          width: (layout.w * unit) - (cMargin * 2),
          height: (layout.h * unit) - (cMargin * 2),
          left: layout.x * unit,
          top: layout.y * unit,
          margin: cMargin,
          padding: cPadding,
        };

      default:
        throw new Error(`Unknown viewportType ${viewportType} supplied to getItemStyle()`);
    }
  }

  getSubTitle() {
    const { item, datasets } = this.props;
    const lastUpdated = getDataLastUpdated({ datasets, visualisation: item.visualisation });
    return lastUpdated ? (
      <span>
        <FormattedMessage id="data_last_updated" />
        : {lastUpdated}
      </span>
    ) : null;
  }

  render() {
    const { item } = this.props;
    const isText = item.type === 'text';
    const isVisualisation = item.type === 'visualisation';
    const style = this.getItemStyle();
    const titleHeight = this.titleEl ?
      this.titleEl.getBoundingClientRect().height :
      TITLE_HEIGHT;

    return (
      <div
        className={`DashboardViewerItem DashboardCanvasItem ${item.type}`}
        style={style}
      >
        {isVisualisation &&
          <div
            className="itemContainer visualisation"
          >
            <div
              className="itemTitle"
              ref={(c) => {
                this.titleEl = c;
              }}
            >
              <h2>{getTitle(item.visualisation)}</h2>
              <span>{this.getSubTitle()}</span>
            </div>
            <AsyncVisualisationViewer
              visualisation={item.visualisation}
              metadata={this.props.metadata ?
                this.props.metadata[item.visualisation.id] : null}
              datasets={this.props.datasets}
              width={style.width - (cPadding * 2)}
              height={style.height ? style.height - (cPadding * 2) - titleHeight : 'auto'}
              showTitle={false}
            />
          </div>
        }
        {isText &&
          <div
            className="itemContainer text"
            style={{
              fontSize: Math.floor(20 * (this.props.canvasWidth / 1280)),
              lineHeight: '1.4em',
            }}
          >
            {item.content}
          </div>
        }
      </div>
    );
  }
}

DashboardViewerItem.propTypes = {
  item: PropTypes.object.isRequired,
  layout: PropTypes.object.isRequired,
  canvasWidth: PropTypes.number.isRequired,
  viewportType: PropTypes.oneOf(['small', 'medium', 'large']),
  datasets: PropTypes.object,
  metadata: PropTypes.object,
};


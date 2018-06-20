import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { FormattedMessage, intlShape, injectIntl } from 'react-intl';
import VisualisationTypeMenu from '../VisualisationTypeMenu';
import LayerMenu from './LayerMenu';
import LayerConfigMenu from './LayerConfigMenu';
import ButtonRowInput from './ButtonRowInput';
import mapLayerSpecTemplate from '../../../containers/Visualisation/mapLayerSpecTemplate';
import { trackEvent } from '../../../utilities/analytics';

require('./MapConfigMenu.scss');

// For making additional, automatic spec changes in response to user-initiated changes
const applyAutomaticSpecChanges = (value) => {
  const newValue = Object.assign({}, value);
  const valueHasKey = key => Object.keys(newValue).indexOf(key) > -1;

  if (valueHasKey('aggregationDataset')) {
    newValue.aggregationColumn = null;
  }

  if (valueHasKey('aggregationGeomColumn')) {
    newValue.aggregationColumn = null;
  }

  if (valueHasKey('showShapeLabelInput')) {
    newValue.shapeLabelColumn = null;
  }

  if (valueHasKey('pointColorColumn')) {
    newValue.pointColorMapping = [];
  }

  return newValue;
};

class MapConfigMenu extends Component {

  constructor() {
    super();
    this.state = {
      selectedLayer: null,
    };

    this.handleAddMapLayer = this.handleAddMapLayer.bind(this);
    this.handleDeleteMapLayer = this.handleDeleteMapLayer.bind(this);
    this.handleChangeMapLayer = this.handleChangeMapLayer.bind(this);
  }

  componentWillReceiveProps(next) {
    const prev = this.props;

    /* If only one geopoint column exists in dataset, select it for map.
    ** Complexity here is due to needing to wait until the dataset columns have loaded before
    ** we can check type of each column.
    */
    const haveVisualisation = next.visualisation && next.visualisation.spec.layers;
    if (haveVisualisation) {
      next.visualisation.spec.layers.forEach((layer, idx) => {
        const { datasetId, layerType } = layer;

        if (datasetId && layerType === 'geo-location') {
          const datasetWasLoaded = prev.datasets[datasetId] && prev.datasets[datasetId].get('columns');
          const datasetIsLoaded = next.datasets[datasetId] && next.datasets[datasetId].get('columns');

          if (!datasetWasLoaded && datasetIsLoaded) {
            const columns = next.datasets[datasetId].get('columns');
            const geopointColumns = columns.filter(column => column.get('type') === 'geopoint').toArray();

            if (geopointColumns.length === 1) {
              // If there is exactly 1 geopoint column, set it as the layer geom for convenience
              this.handleChangeMapLayer(idx, { geom: geopointColumns[0].get('columnName') });
            }
          }
        }
      });
    }
  }

  handleAddMapLayer() {
    const { intl, visualisation, onChangeSpec } = this.props;
    const title = intl.formatMessage(
      { id: 'untitled_layer' },
      { count: visualisation.spec.layers.length + 1 }
    );
    const layers = visualisation.spec.layers.slice();
    layers.push({ ...mapLayerSpecTemplate, title });
    onChangeSpec({ layers });
  }

  handleDeleteMapLayer(layerIndex) {
    const layers = this.props.visualisation.spec.layers.map(item => item);
    const newLayers = layers.slice();

    newLayers.splice(layerIndex, 1);
    this.props.onChangeSpec({ layers: newLayers });
  }

  handleChangeMapLayer(layerIndex, userChange) {
    const value = applyAutomaticSpecChanges(userChange);
    const clonedLayer = Object.assign({}, this.props.visualisation.spec.layers[layerIndex], value);
    const layers = this.props.visualisation.spec.layers.map(item => item);
    layers[layerIndex] = clonedLayer;

    if (userChange.layerType) {
      trackEvent('Selected map layer type', userChange.layerType);
    }

    if (Object.keys(value).indexOf('datasetId') > -1) {
      const { datasetId } = value;

      this.props.loadDataset(datasetId);
    } else if (Object.keys(value).indexOf('aggregationDataset') > -1) {
      const { aggregationDataset } = value;

      if (aggregationDataset) {
        // Can be null when user is *removing* the aggregation dataset
        this.props.loadDataset(aggregationDataset);
      }
    }

    this.props.onChangeSpec({ layers });
  }

  handlePopupChange(columnNames) {
    const popup = columnNames.map(columnName => ({
      column: columnName,
    }));
    this.props.onChangeSpec({ popup });
  }

  render() {
    const { visualisation, onChangeSpec } = this.props;
    const { spec } = visualisation;

    return (
      <div
        className="MapConfigMenu"
      >
        <div className="contents">
          {this.state.selectedLayer === null ?
            <div>
              <div
                className="drawer"
              >
                <VisualisationTypeMenu
                  onChangeVisualisationType={this.props.onChangeVisualisationType}
                  visualisation={visualisation}
                  disabled={false}
                />
              </div>
              <LayerMenu
                layers={this.props.visualisation.spec.layers}
                metadata={this.props.metadata}
                activeLayer={this.state.activeLayer}
                onAddLayer={() => this.handleAddMapLayer()}
                onDeleteMapLayer={layerIndex => this.handleDeleteMapLayer(layerIndex)}
                onSelectLayer={layerIndex => this.setState({ selectedLayer: layerIndex })}
                onChangeMapLayer={this.handleChangeMapLayer}
              />
              <div
                className="drawer"
              >
                <ButtonRowInput
                  options={['street', 'satellite', 'terrain'].map(item => ({
                    label: <FormattedMessage id={item} />,
                    value: item,
                  }))}
                  selected={visualisation.spec.baseLayer}
                  label={<FormattedMessage id="base_map" />}
                  onChange={baseLayer => onChangeSpec({ baseLayer })}
                />
              </div>
            </div>
          :
            <LayerConfigMenu
              layer={spec.layers[this.state.selectedLayer]}
              layerIndex={this.state.selectedLayer}
              metadata={this.props.metadata}
              onDeselectLayer={() => this.setState({ selectedLayer: null })}
              datasets={this.props.datasets}
              rasters={this.props.rasters}
              datasetOptions={this.props.datasetOptions}
              onChangeMapLayer={this.handleChangeMapLayer}
              onSave={this.props.onSave}
              disabled={visualisation.awaitingResponse}
            />
          }
        </div>
      </div>
    );
  }
}

MapConfigMenu.propTypes = {
  intl: intlShape,
  visualisation: PropTypes.object.isRequired,
  metadata: PropTypes.object,
  datasets: PropTypes.object.isRequired,
  rasters: PropTypes.object.isRequired,
  onChangeSpec: PropTypes.func.isRequired,
  aggregationOptions: PropTypes.array.isRequired,
  onChangeSourceDataset: PropTypes.func.isRequired,
  onSave: PropTypes.func.isRequired,
  onChangeVisualisationType: PropTypes.func.isRequired,
  onChangeMapLayer: PropTypes.func,
  datasetOptions: PropTypes.array.isRequired,
  loadDataset: PropTypes.func.isRequired,
};

export default injectIntl(MapConfigMenu);

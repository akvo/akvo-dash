import React, { PropTypes, Component } from 'react';
import VisualisationTypeMenu from '../VisualisationTypeMenu';
import LayerMenu from './LayerMenu';
import LayerConfigMenu from './LayerConfigMenu';
import ButtonRowInput from './ButtonRowInput';
import mapLayerSpecTemplate from '../../../containers/Visualisation/mapLayerSpecTemplate';

require('./MapConfigMenu.scss');

export default class MapConfigMenu extends Component {

  constructor() {
    super();
    this.state = {
      selectedLayer: null,
    };

    this.handleAddMapLayer = this.handleAddMapLayer.bind(this);
    this.handleDeleteMapLayer = this.handleDeleteMapLayer.bind(this);
    this.handleChangeMapLayer = this.handleChangeMapLayer.bind(this);
  }

  handleAddMapLayer() {
    const title = `Untitled Layer ${this.props.visualisation.spec.layers.length + 1}`;
    const layers = this.props.visualisation.spec.layers.map(item => item);
    layers.push(Object.assign({}, mapLayerSpecTemplate, { title }));
    this.props.onChangeSpec({ layers });
  }

  handleDeleteMapLayer(layerIndex) {
    const layers = this.props.visualisation.spec.layers.map(item => item);
    layers.splice(layerIndex, 1);

    this.props.onChangeSpec({ layers });
  }

  handleChangeMapLayer(layerIndex, value) {
    const clonedLayer = Object.assign({}, this.props.visualisation.spec.layers[layerIndex], value);
    const layers = this.props.visualisation.spec.layers.map(item => item);
    layers[layerIndex] = clonedLayer;

    // Temporary shim while we still define datasetId on the top-level visualisation
    if (Object.keys(value).indexOf('datasetId') > -1) {
      const datasetId = value.datasetId;
      this.props.onChangeSourceDataset(datasetId, { layers });
    } else {
      this.props.onChangeSpec({ layers });
    }
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
                  options={['street', 'satellite', 'terrain']}
                  selected={visualisation.spec.baseLayer}
                  label="Base map"
                  onChange={baseLayer => onChangeSpec({ baseLayer })}
                />
              </div>
            </div>
          :
            <LayerConfigMenu
              layer={spec.layers[this.state.selectedLayer]}
              layerIndex={this.state.selectedLayer}
              onDeselectLayer={() => this.setState({ selectedLayer: null })}
              datasets={this.props.datasets}
              datasetOptions={this.props.datasetOptions}
              onChangeMapLayer={this.handleChangeMapLayer}
              onSave={this.props.onSave}
            />
          }
        </div>
        <div
          className="saveContainer noSelect"
        >
          <button
            className="saveButton clickable"
            onClick={this.props.onSave}
          >
            Save
          </button>
        </div>
      </div>
    );
  }
}

MapConfigMenu.propTypes = {
  visualisation: PropTypes.object.isRequired,
  datasets: PropTypes.object.isRequired,
  onChangeSpec: PropTypes.func.isRequired,
  aggregationOptions: PropTypes.array.isRequired,
  onChangeSourceDataset: PropTypes.func.isRequired,
  onSave: PropTypes.func.isRequired,
  onChangeVisualisationType: PropTypes.func.isRequired,
  datasetOptions: PropTypes.array.isRequired,
};

import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { injectIntl, intlShape } from 'react-intl';
import ContextMenu from '../../common/ContextMenu';
import ToggleInput from '../../common/ToggleInput';

require('./LayerMenuItem.scss');

class LayerMenuItem extends Component {

  constructor() {
    super();
    this.state = {
      showOverflow: false,
      workingTitle: '',
    };
  }

  componentWillMount() {
    this.setState({
      workingTitle: this.props.layer.title,
    });
  }

  render() {
    const {
      layer,
      layerIndex,
      titleEditMode,
      onBeginTitleEdit,
      onEndTitleEdit,
      onChangeTitle,
      onDeleteLayer,
      onSelectLayer,
      intl,
    } = this.props;

    return (
      <li
        className="LayerMenuItem"
      >
        {titleEditMode ?
          <div
            className="titleInputContainer"
          >
            <input
              type="text"
              value={this.state.workingTitle}
              onChange={evt => this.setState({
                workingTitle: evt.target.value,
              })}
            />
            <button
              className="clickable"
              onClick={() => {
                onChangeTitle(this.state.workingTitle);
                onEndTitleEdit();
              }}
            >
              ✓
            </button>
          </div>
          :
          <div
            className="container"
          >
            <span
              className="titleContainer"
            >
              <span
                onClick={() => onSelectLayer(layerIndex)}
                className="clickable title"
                data-test-id="layer"
              >
                {layer.title}
              </span>
            </span>
            <span
              className="toggleContainer notImplemented"
            >
              <ToggleInput
                checked={layer.visible}
                onChange={() => this.props.onSetLayerVisible(!layer.visible)}
              />
            </span>
            <span
              className="overflowButtonContainer"
            >
              <button
                className={`clickable overflowButton noSelect
                  ${this.state.showOverflow ? 'active' : 'inactive'}`}
                onClick={() => this.setState({ showOverflow: true })}
              >
                ● ● ●
              </button>
              {this.state.showOverflow &&
                <ContextMenu
                  style={{
                    top: '1.8rem',
                  }}
                  arrowClass="topRight"
                  options={[
                    { value: 'rename', label: intl.formatMessage({ id: 'rename' }) },
                    { value: 'delete', label: intl.formatMessage({ id: 'delete' }) },
                  ]}
                  onOptionSelected={(option) => {
                    if (option === 'rename') {
                      onBeginTitleEdit();
                    } else if (option === 'delete') {
                      onDeleteLayer();
                    }
                  }}
                  onWindowClick={() => this.setState({ showOverflow: false })}
                />
              }
            </span>
          </div>
        }
      </li>
    );
  }
}

LayerMenuItem.propTypes = {
  intl: intlShape,
  layer: PropTypes.object.isRequired,
  layerIndex: PropTypes.number.isRequired,
  titleEditMode: PropTypes.bool.isRequired,
  onBeginTitleEdit: PropTypes.func.isRequired,
  onEndTitleEdit: PropTypes.func.isRequired,
  onChangeTitle: PropTypes.func.isRequired,
  onDeleteLayer: PropTypes.func.isRequired,
  onSelectLayer: PropTypes.func.isRequired,
  onSetLayerVisible: PropTypes.func.isRequired,

};

export default injectIntl(LayerMenuItem);

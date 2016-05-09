import React, { Component, PropTypes } from 'react';

function sourceComponent(source) {
  switch (source.type) {
    case 'DATA_FILE':
      return <span>Data file</span>;
    case 'LINK':
      return <span>Link</span>;
    default:
      return <span>{source.type}</span>;
  }
}

function fileName(source) {
  switch (source.type) {
    case 'DATA_FILE':
      return source.name;
    case 'LINK':
      return source.url.substring(source.url.lastIndexOf('/') + 1);
    default: return 'Unknown';
  }
}

const showDatasetHeaderToggle = dataset =>
  dataset.source.kind === 'DATA_FILE' || dataset.source.kind === 'LINK';

export default class Settings extends Component {

  constructor() {
    super();
    this.handleUpdate = this.handleUpdate.bind(this);
  }

  handleUpdate() {
    if (this.refs.datasetHeaderStatusToggle) {
      this.props.onChangeSettings({
        name: this.refs.datasetNameInput.value,
        hasColumnHeaders: this.refs.datasetHeaderStatusToggle.checked,
      });
    } else {
      this.props.onChangeSettings({
        name: this.refs.datasetNameInput.value,
      });
    }
  }

  render() {
    const { dataset } = this.props;

    return (
      <div className="Settings">
        <dl>
          <dt>Source: </dt>
          <dd>{sourceComponent(dataset.source)}</dd>

          <dt>File name: </dt>
          <dd>{fileName(dataset.source)}</dd>

          <dt>Dataset name: </dt>
          <dd>
            <input
              defaultValue={dataset.name}
              className="datasetNameInput"
              onChange={this.handleUpdate}
              ref="datasetNameInput"
              type="text"
            />
          </dd>
          { showDatasetHeaderToggle(dataset) &&
            <span>
              <dt>Data has column headers:</dt>
              <dd>
                <input
                  type="checkbox"
                  className="datasetHeaderStatusToggle"
                  defaultChecked={dataset.hasColumnHeaders }
                  ref="datasetHeaderStatusToggle"
                  onChange={this.handleUpdate}
                />
              </dd>
            </span>
          }
        </dl>
      </div>
    );
  }
}

Settings.propTypes = {
  dataset: PropTypes.object.isRequired,
  onChangeSettings: PropTypes.func.isRequired,
};

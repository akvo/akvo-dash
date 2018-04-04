import React, { Component } from 'react';
import PropTypes from 'prop-types';
import Measure from 'react-measure';

export default class ResponsiveWrapper extends Component {
  static propTypes = {
    children: PropTypes.func.isRequired,
  }

  state = {
    dimensions: {
      width: -1,
      height: -1,
    },
  }

  render() {
    const { width, height } = this.state.dimensions;
    const shouldRender = width > 0 && height > 0;

    return (
      <Measure
        bounds
        onResize={(contentRect) => {
          this.setState({ dimensions: contentRect.bounds });
        }}
      >
        {({ measureRef }) => (
          <div ref={measureRef} style={{ width: '100%', height: '100%' }}>
            {shouldRender && this.props.children({ width, height })}
          </div>
        )}
      </Measure>
    );
  }
}

import React from 'react';
import PropTypes from 'prop-types';
import { LegendOrdinal } from '@vx/legend';
import { scaleOrdinal } from '@vx/scale';

import { replaceLabelIfValueEmpty } from '../../utilities/chart';
import './Legend.scss';
import LegendShape from './LegendShape';

const Legend = ({
  data,
  title,
  horizontal = false,
  activeItem,
  colorMapping = {},
  ...rest
}) => {
  const ordinalColor = scaleOrdinal({ domain: data, range: [] });
  return (
    <div className={`legend ${horizontal ? 'legend-horizontal' : ''}`}>
      {title && <h4>{title}</h4>}
      <LegendOrdinal
        {...rest}
        shapeMargin="0"
        labelMargin="0 0 0 4px"
        itemMargin="0 5px"
        scale={ordinalColor}
        shape={({ label: { datum }, ...shapeRest }) => (
          <LegendShape isActive={activeItem === datum} {...shapeRest} />
        )}
        fill={({ datum }) => colorMapping[replaceLabelIfValueEmpty(datum)]}
      />
    </div>
  );
};

Legend.propTypes = {
  data: PropTypes.arrayOf(PropTypes.string),
  colorMapping: PropTypes.object.isRequired,
  onClick: PropTypes.func,
  horizontal: PropTypes.bool,
  title: PropTypes.string,
  activeItem: PropTypes.string,
};

export default Legend;

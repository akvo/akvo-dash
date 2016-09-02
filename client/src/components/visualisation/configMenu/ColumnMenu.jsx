import React, { PropTypes } from 'react';
import SelectMenu from '../../common/SelectMenu';

export default function ColumnMenu(props) {
  return (
    <div className="inputGroup">
      <label htmlFor={props.name}>
        Dataset column:
      </label>
      <SelectMenu
        name={props.name}
        placeholder="Choose a dataset column..."
        value={props.choice}
        options={props.options}
        onChange={props.onChange}
      />
    </div>
  );
}

ColumnMenu.propTypes = {
  name: PropTypes.string.isRequired,
  choice: PropTypes.string,
  options: PropTypes.array.isRequired,
  onChange: PropTypes.func.isRequired,
};

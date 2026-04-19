var SaikuOlapQueryTemplate = {
  "queryModel": {
    "axes": {
      "FILTER": {
        "mdx": null,
        "filters": [],
        "sortOrder": null,
        "sortEvaluationLiteral": null,
        "hierarchizeMode": null,
        "location": "FILTER",
        "hierarchies": [],
        "nonEmpty": false
      },
      "COLUMNS": {
        "mdx": null,
        "filters": [],
        "sortOrder": null,
        "sortEvaluationLiteral": null,
        "hierarchizeMode": null,
        "location": "COLUMNS",
        "hierarchies": [],
        "nonEmpty": true
      },
      "ROWS": {
        "mdx": null,
        "filters": [],
        "sortOrder": null,
        "sortEvaluationLiteral": null,
        "hierarchizeMode": null,
        "location": "ROWS",
        "hierarchies": [],
        "nonEmpty": true
      }
    },
    "visualTotals": false,
    "visualTotalsPattern": null,
    "lowestLevelsOnly": false,
    "details": {
      "axis": "COLUMNS",
      "location": "BOTTOM",
      "measures": []
    },
    "calculatedMeasures": [],
    "calculatedMembers": []
  },
  "queryType": "OLAP",
  "type": "QUERYMODEL"
};

var SaikuOlapQueryHelper = function(query) {
	this.query = query;
};

SaikuOlapQueryHelper.prototype.model = function() {
	return this.query.model;
};

SaikuOlapQueryHelper.prototype.clearAxis = function(axisName) {
  this.model().queryModel.axes[axisName].hierarchies = [];
};

SaikuOlapQueryHelper.prototype.getHierarchy = function(name) {
  var _searchFunction = function(he) {
    return (he && he.name == name);
  };

  for (var axisName in this.model().queryModel.axes) {
      var axis = this.model().queryModel.axes[axisName];
      var hierarchy = _.find(axis.hierarchies, _searchFunction);
      if (hierarchy) {
        return hierarchy;
      }
    }
    return null;
};

SaikuOlapQueryHelper.prototype.moveHierarchy = function(fromAxis, toAxis, hierarchy, position) {
  var h = this.getHierarchy(hierarchy);
  var i = this.model().queryModel.axes[fromAxis].hierarchies.indexOf(h);
  var target = this.model().queryModel.axes[toAxis].hierarchies;
  this.model().queryModel.axes[fromAxis].hierarchies.splice(i,1);
  if (typeof position != "undefined" && position > -1 && target.length > position) {
      target.splice(position, 0, h);
      return;
  }
  target.push(h);

};

SaikuOlapQueryHelper.prototype.removeHierarchy = function(hierarchy) {
  var h = this.getHierarchy(hierarchy);
  if (!h) {
    return null;
  }
  var axis = this.findAxisForHierarchy(hierarchy);
  if (axis) {
    var i = axis.hierarchies.indexOf(h);
      axis.hierarchies.splice(i,1);
  }
  return h;
};

SaikuOlapQueryHelper.prototype.findAxisForHierarchy = function(hierarchy) {
  for (var axisName in this.model().queryModel.axes) {
    var axis = this.model().queryModel.axes[axisName];
    if (axis.hierarchies && axis.hierarchies.length > 0) {
      for (var i = 0, len = axis.hierarchies.length; i < len; i++) {
        if (axis.hierarchies[i].name == hierarchy) {
          return axis;
        }
      }
    }
  }
  return null;
};

SaikuOlapQueryHelper.prototype.getAxis = function(axisName) {
  if (axisName in this.model().queryModel.axes) {
    return this.model().queryModel.axes[axisName];
  }
  Saiku.log("Cannot find axis: " + axisName);
};

SaikuOlapQueryHelper.prototype.removeFilter = function(filterable, flavour) {
    if (flavour && filterable.filters.length > 1) {
      var removeIndex = -1;
      for (var i = 0, len = filterable.filters.length; i < len; i++) {
        if (filterable.filters[i].flavour == flavour) {
          removeIndex = i;
        }
      }
      if (removeIndex && removeIndex >= 0) {
        filterable.filters.splice(removeIndex, 0);
      }
    } else {
      filterable.filters = [];
    }
};

SaikuOlapQueryHelper.prototype.setDefaultFilter = function(hierarchy, level, value){
  var strip = level.replace("[", "");
  strip = strip.replace("]","");
  this.includeLevel("FILTER", hierarchy, strip);
  var h = this.getHierarchy(hierarchy).levels[strip];
  h.selection = { "type": "INCLUSION", "members": [] };
  h.selection["parameterName"] = "default_filter_"+strip;
  if(!this.model().parameters){
    this.model().parameters = {};
  }
  var k = "default_filter_"+strip;
  this.model().parameters[k] = value;
};

SaikuOlapQueryHelper.prototype.getLevelForParameter = function(parameter){
  var m;
  var axes = this.model().queryModel.axes;
  _.each(axes, function(a){
    var hier = a.hierarchies;
    _.each(hier, function(h){
      _.each(h.levels, function(l){
        if(l.selection && l.selection["parameterName"] && l.selection["parameterName"] === parameter){
          m = {hierarchy:h, level:l};
          return false;
        }
      });
    });
  });
  return m;
};

SaikuOlapQueryHelper.prototype.getSelectionsForParameter = function(parameter){
  var m;
  var axes = this.model().queryModel.axes;
  _.each(axes, function(a){
    var hier = a.hierarchies;
    _.each(hier, function(h){
      _.each(h.levels, function(l){
        if(l.selection && l.selection["parameterName"] && l.selection["parameterName"] === parameter){
          m = l.selection.members;
          return false;
        }
      });
    });
  });
  return m;
};

SaikuOlapQueryHelper.prototype.setSelectionsForParameter = function(parameter, selections){
  var m;
  var axes = this.model().queryModel.axes;
  _.each(axes, function(a){
    var hier = a.hierarchies;
    _.each(hier, function(h){
      _.each(h.levels, function(l){
        if(l.selection && l.selection["parameterName"] && l.selection["parameterName"] === parameter){
          l.selection.members = selections;
          return false;
        }
      });
    });
  });
};

SaikuOlapQueryHelper.prototype.removeParameter = function(hierarchy, level) {
  hierarchy = this.getHierarchy(hierarchy);
  if (hierarchy && hierarchy.levels.hasOwnProperty(level)) {
    var parameterName = hierarchy.levels[level].hasOwnProperty('selection')
                        ? hierarchy.levels[level].selection['parameterName']
                        : null;
    delete this.model().parameters[parameterName];
  }
};

var buildUniqueName = function(level, name) {
  return level.hierarchy.name + ".[" + level.level.name + "].[" + name + "]";
};

SaikuOlapQueryHelper.prototype.addtoSelection = function(membername, level){
  if(level.level.selection.members === undefined){
    level.selection.members = [];
  }

  var found = false;
  _.each(level.level.selection.members, function(m){
    var mUniqueName = buildUniqueName(level, m.name);
    if (buildUniqueName(level, m.name) === buildUniqueName(level, membername)) {
      found = true;
    }
  });

  if(!found) {
    level.level.selection.members.push({
      uniqueName: buildUniqueName(level, membername),
      caption: membername
    })
  }
};

SaikuOlapQueryHelper.prototype.includeCmember = function(hierarchy, level){
  var mHierarchy = this.getHierarchy(hierarchy);
  if (mHierarchy) {
    mHierarchy.cmembers[level]= level;
  }
}
SaikuOlapQueryHelper.prototype.includeLevel = function(axis, hierarchy, level, position) {
    var mHierarchy = this.getHierarchy(hierarchy);
    if (mHierarchy) {
      mHierarchy.levels[level] = { name: level };
    } else {
      mHierarchy = { "name": hierarchy, "levels": {}, "cmembers": {} };
      mHierarchy.levels[level] = { name: level };
    }

    var existingAxis = this.findAxisForHierarchy(hierarchy);
    if (existingAxis) {
      this.moveHierarchy(existingAxis.location, axis, hierarchy, position);
    } else {
      var _axis = this.model().queryModel.axes[axis];
      if (_axis) {
        if (typeof position != "undefined" && position > -1 && _axis.hierarchies.length > position) {
          _axis.hierarchies.splice(position, 0, mHierarchy);
          return;
        }
        _axis.hierarchies.push(mHierarchy);
      } else {
        Saiku.log("Cannot find axis: " + axis + " to include Level: " + level);
      }
    }
};

SaikuOlapQueryHelper.prototype.includeLevelCalculatedMember = function(axis, hierarchy, level, uniqueName, position) {
    var mHierarchy = this.getHierarchy(hierarchy);
    if (mHierarchy) {
      mHierarchy.cmembers[uniqueName] = uniqueName;
    } else {
      mHierarchy = { "name": hierarchy, "levels": {}, "cmembers": {} };
      mHierarchy.cmembers[uniqueName] = uniqueName;
    }

    var existingAxis = this.findAxisForHierarchy(hierarchy);
    if (existingAxis) {
      this.moveHierarchy(existingAxis.location, axis, hierarchy, -1);
    } else {
      var _axis = this.model().queryModel.axes[axis];
      if (_axis) {
        if (typeof position != "undefined" && position > -1 && _axis.hierarchies.length > position) {
          _axis.hierarchies.splice(position, 0, mHierarchy);
          return;
        }
        _axis.hierarchies.push(mHierarchy);
      } else {
        Saiku.log("Cannot find axis: " + axis + " to include Level: " + level);
      }
    }
};

SaikuOlapQueryHelper.prototype.removeLevel = function(hierarchy, level) {
  var hier = hierarchy;

  hierarchy = this.getHierarchy(hierarchy);

  if (hierarchy && hierarchy.levels.hasOwnProperty(level)) {
    delete hierarchy.levels[level];

    if (hierarchy && _.isEmpty(hierarchy.levels)) {
      this.removeHierarchy(hier);
    }
  }
};

SaikuOlapQueryHelper.prototype.removeLevelCalculatedMember = function(hierarchy, level) {
  hierarchy = this.getHierarchy(hierarchy);
  if (hierarchy && hierarchy.cmembers.hasOwnProperty(level)) {
    delete hierarchy.cmembers[level];
  }
};

SaikuOlapQueryHelper.prototype.removeAllLevelCalculatedMember = function(hierarchy) {
  hierarchy = this.getHierarchy(hierarchy);
  hierarchy.cmembers = {};

};


SaikuOlapQueryHelper.prototype.includeMeasure = function(measure) {
  var measures = this.model().queryModel.details.measures,
      len = measures.length,
      i,
      aux = false;
  if (measures && !(_.isEmpty(measures))) {
    for (i = 0; i < len; i++) {
      if (measures[i].name === measure.name) {
        aux = true;
        break;
      }
      else {
        aux = false;
      }
    }

    if (aux === false) {
      measures.push(measure);
    }
  }
  else {
    measures.push(measure);
  }
};

SaikuOlapQueryHelper.prototype.removeMeasure = function(name) {
  var measures = this.query.model.queryModel.details.measures;
  var removeMeasure = _.findWhere(measures , { name: name });
  if (removeMeasure && _.indexOf(measures, removeMeasure) > -1) {
	  measures = _.without(measures, removeMeasure);
  }
};

SaikuOlapQueryHelper.prototype.clearMeasures = function() {
  this.model().queryModel.details.measures = [];
};

SaikuOlapQueryHelper.prototype.setMeasures = function(measures) {
  this.model().queryModel.details.measures = measures;
};

SaikuOlapQueryHelper.prototype.addCalculatedMeasure = function(measure) {
  if (measure) {
    this.removeCalculatedMeasure(measure.name);
    this.model().queryModel.calculatedMeasures.push(measure);
  }
};

SaikuOlapQueryHelper.prototype.editCalculatedMeasure = function(name, measure) {
  if (measure) {
    this.removeCalculatedMeasure(name);
    this.removeCalculatedMember(name);
    this.model().queryModel.calculatedMeasures.push(measure);
  }
};

SaikuOlapQueryHelper.prototype.removeCalculatedMeasure = function(name) {
  var measures = this.model().queryModel.calculatedMeasures;
  var removeMeasure = _.findWhere(measures , { name: name });
  if (removeMeasure && _.indexOf(measures, removeMeasure) > -1) {
    measures = _.without(measures, removeMeasure);
    this.model().queryModel.calculatedMeasures = measures;
  }
};

SaikuOlapQueryHelper.prototype.getCalculatedMeasures = function() {
  var ms = this.model().queryModel.calculatedMeasures;
  if (ms) {
    return ms;
  }
  return null;
};

SaikuOlapQueryHelper.prototype.addCalculatedMember = function(measure) {
  if (measure) {
    this.removeCalculatedMember(measure.name);
    this.model().queryModel.calculatedMembers.push(measure);
  }
};

SaikuOlapQueryHelper.prototype.editCalculatedMember = function(name, measure) {
  if (measure) {
    this.removeCalculatedMeasure(name);
    this.removeCalculatedMember(name);
    this.model().queryModel.calculatedMembers.push(measure);
  }
};

SaikuOlapQueryHelper.prototype.removeCalculatedMember = function(name) {
  var measures = this.model().queryModel.calculatedMembers;
  var removeMeasure = _.findWhere(measures , { name: name });
  if (removeMeasure && _.indexOf(measures, removeMeasure) > -1) {
    measures = _.without(measures, removeMeasure);
    this.model().queryModel.calculatedMembers = measures;
  }
};

SaikuOlapQueryHelper.prototype.getCalculatedMembers = function() {
  var ms = this.model().queryModel.calculatedMembers;
  if (ms) {
    return ms;
  }
  return null;
};

SaikuOlapQueryHelper.prototype.swapAxes = function() {
  var axes = this.model().queryModel.axes;
  var tmpAxis = axes.ROWS;
  tmpAxis.location = 'COLUMNS';
  axes.ROWS = axes.COLUMNS;
  axes.ROWS.location = 'ROWS';
  axes.COLUMNS = tmpAxis;
};

SaikuOlapQueryHelper.prototype.nonEmpty = function(nonEmpty) {
  if (nonEmpty) {
    this.model().queryModel.axes.ROWS.nonEmpty = true;
    this.model().queryModel.axes.COLUMNS.nonEmpty = true;
  } else {
    this.model().queryModel.axes.ROWS.nonEmpty = false;
    this.model().queryModel.axes.COLUMNS.nonEmpty = false;
  }
};





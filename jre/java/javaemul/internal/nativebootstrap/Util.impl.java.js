// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * Impl hand rolled.
 */
goog.module('nativebootstrap.Util$impl');


const Reflect = goog.require('goog.reflect');
const jre = goog.require('jre');
const Constructor = goog.require('javaemul.internal.Constructor');


/**
 * Miscellaneous utility functions.
 */
class Util {
  /**
   * Return the value defined by a goog.define or the default value
   * if it is not defined.
   *
   * @param {string} name
   * @param {?string=} opt_defaultValue
   * @return {?string}
   * @public
   */
  static $getDefine(name, opt_defaultValue) {
    // Default the optional param. Note that we are not using the common
    // 'opt_value || default_value' pattern otherwise that would replace
    // empty string with null value.
    var defaultValue = opt_defaultValue == null ? null : opt_defaultValue;
    var rv = goog.getObjectByName(name);
    return rv == null ? defaultValue : String(rv);
  }

  /**
   * @param {Constructor} ctor
   * @param {string} name
   * @public
   */
  static $setClassMetadata(ctor, name) {
    ctor.prototype.$$classMetadata = [Util.$makeClassName(name), Util.TYPE_CLASS];
  }

  /**
   * // TODO(b/79389970): change ctor to Function
   * @param {Object} ctor
   * @param {string} name
   * @public
   */
  static $setClassMetadataForInterface(ctor, name) {
    ctor.prototype.$$classMetadata = [Util.$makeClassName(name), Util.TYPE_INTERFACE];
  }

  /**
   * @param {Constructor} ctor
   * @param {string} name
   * @public
   */
  static $setClassMetadataForEnum(ctor, name) {
    ctor.prototype.$$classMetadata = [Util.$makeClassName(name), Util.TYPE_ENUM];
  }

  /**
   * @param {Constructor} ctor
   * @param {string} name
   * @param {string} shortName
   * @public
   */
  static $setClassMetadataForPrimitive(ctor, name, shortName) {
    ctor.prototype.$$classMetadata = [Util.$makeClassName(name), Util.TYPE_PRIMITIVE, shortName];
    // Primitives also marked separately as $isPrimitiveType works even without
    // class metadata.
    ctor.prototype.$$isPrimitive = true;
  }

  /**
   * Returns whether the provided ctor represents primitive type.
   * @param {Constructor} ctor
   * @return {boolean}
   * @public
   */
  static $isPrimitiveType(ctor) {
    return !!ctor && ctor.prototype.$$isPrimitive;
  }

  /**
   * @param {Constructor} ctor
   * @return {string}
   * @public
   */
  static $extractClassName(ctor) {
    if (jre.classMetadata == 'SIMPLE') {
      return ctor.prototype.$$classMetadata[0];
    } else if (jre.classMetadata == 'STRIPPED') {
      return Util.$getGeneratedClassName_(ctor);
    } else {
      throw new Error('Incorrect value: ' + jre.classMetadata);
    }
  }

  /**
   * @param {Constructor} ctor
   * @return {string}
   * @public
   */
  static $extractPrimitiveShortName(ctor) {
    if (jre.classMetadata == 'SIMPLE') {
      return ctor.prototype.$$classMetadata[2];
    } else if (jre.classMetadata == 'STRIPPED') {
      return Util.$getGeneratedClassName_(ctor);
    } else {
      throw new Error('Incorrect value: ' + jre.classMetadata);
    }
  }

  /**
   * @param {Constructor} ctor
   * @return {string}
   * @private
   */
  static $getGeneratedClassName_(ctor) {
    return Reflect.cache(ctor.prototype, '$$generatedClassName', function() {
      // valueOf hack makes JsCompiler think that this is side effect free.
      return 'Class$obf_' + {
        valueOf() {
          return ++Util.$nextUniqId_;
        }
      };
    });
  }

  /**
   * @param {Constructor} ctor
   * @return {number}
   * @public
   */
  static $extractClassType(ctor) {
    if (jre.classMetadata == 'SIMPLE') {
      return ctor.prototype.$$classMetadata[1];
    } else if (jre.classMetadata == 'STRIPPED') {
      return Util.TYPE_CLASS;
    } else {
      throw new Error('Incorrect value: ' + jre.classMetadata);
    }
  }

  /**
   * Returns whether the "from" class can be cast to the "to" class.
   *
   * Unlike instanceof, this function operates on classes instead of
   * instances.
   * @param {Function} fromClass
   * @param {Function} toClass
   * @return {boolean}
   * @public
   */
  static $canCastClass(fromClass, toClass) {
    return (
        fromClass != null &&
        (fromClass == toClass || fromClass.prototype instanceof toClass));
  }

  /**
   * Create a function that applies the specified jsFunctionMethod on itself,
   * and copies `instance`' properties to itself.
   *
   * @param {T} jsFunctionMethod
   * @param {U} instance
   * @param {function(U,T):void} copyMethod
   * @return {!T}
   * @template T,U
   * @public
   */
  static $makeLambdaFunction(jsFunctionMethod, instance, copyMethod) {
    var lambda = function() {
      return jsFunctionMethod.apply(lambda, arguments);
    };
    copyMethod(instance, lambda);
    return lambda;
  }

  /**
   * Asserts if class for the provided instance has initialized.
   *
   * @param {*} instance
   */
  static $assertClinit(instance) {
    if (COMPILED) {
      return;
    }

    const clinit = instance.constructor['$clinit'];
    if (clinit && clinit.name == '$clinit' /* i.e. not re-written yet */) {
      throw new Error(Util.getInitializationError_(instance.constructor));
    }
  }

  static getInitializationError_(ctor) {
    let javaCtor = ctor, childCtor = ctor;
    while (!javaCtor.hasOwnProperty('$clinit')) {
      childCtor = javaCtor;
      // Get the super constructor.
      javaCtor = Object.getPrototypeOf(javaCtor.prototype).constructor;
    }

    return javaCtor != childCtor ?
        `Java class ${javaCtor.name} is extended by ${childCtor.name} but not initialized.` +
            `This usually happens if you are extending a class without JsConstructor.` :
        `Java class ${javaCtor.name} is instantiated but not initialized.` +
            `This usually happens if you are instantiating a class without JsConstructor ` +
            `or missing $clinit call in native.js file.`;
  }

  /**
   * Helper to accept a reference to something that should be synchronized on.
   * No synchronization is actually necessary since JS is singlethreaded but
   * it's important that the parameter be passed since the accessing of it
   * may have side effects that should be preserved.
   *
   * @param {*} value The value to synchronize on.
   * @public
   */
  static $synchronized(value) {}

  /**
   * Runs inline static field initializers.
   * @public
   */
  static $clinit() {}

  /**
   * Helper function used for metadata obfuscation, string replacement passes
   * can be targeted at this bottleneck.
   *
   * TODO(b/31782198): Because J2ClPass runs before ReplaceStrings and inlines
   * functions, the ReplaceStrings pass never sees calls to $setClassMetadata,
   * which makes this function neccessary.
   *
   * @param {string} className
   * @return {string}
   */
  static $makeClassName(className) {
    return className;
  }

  /**
   * Helper function used for enum obfuscation, string replacement passes
   * can be targeted at this bottleneck.
   *
   * @param {string} enumName
   * @return {string}
   */
  static $makeEnumName(enumName) {
    return enumName;
  }
}


/**
 * @private {number}
 */
Util.$nextUniqId_ = 1000;

/**
 * @type {number}
 */
Util.TYPE_CLASS = 0;

/**
 * @type {number}
 */
Util.TYPE_INTERFACE = 1;

/**
 * @type {number}
 */
Util.TYPE_ENUM = 2;

/**
 * @type {number}
 */
Util.TYPE_PRIMITIVE = 3;



/**
 * Exported class.
 */
exports = Util;

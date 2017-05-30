var r = replikativ.js;

var user = "mail:alice@stechuhr.de";
var ormapId = cljs.core.uuid("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";

var props = {captures: []};

var streamEvalFuncs = {"add": function(old, params) {
  var oldCaptures = old.captures;
  var newCaptures = oldCaptures.push(params);
  return {captures: newCaptures};
}};

function logError(err) {
  console.log(err);
}

var sync = {};

function setupReplikativ() {
  r.newMemStore().then(function(store) {
    sync.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    sync.peer = peer;
    return r.createStage(user, peer);
  }, logError).then(function(stage) {
    sync.stage = stage;
    sync.stream = r.streamIntoIdentity(stage, user, ormapId, streamEvalFuncs, props)
    return r.createOrMap(stage, {id: ormapId, description: "captures"})
  }, logError).then(function() {
    return r.connect(sync.stage, uri);
  }, logError).then(function () {
    console.log("stage connected!")
  }, logError);
}

function checkIt(value) {
  r.associate(sync.stage, user, ormapId, hasch.core.uuid(cljs.core.js__GT_clj(value)), [["add", value]])
    .then(function(result) {
      console.log("foo associated with 42");
    }, logError);
}

setupReplikativ();



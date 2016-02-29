(ns mbuczko.category.tree
  (:require [cheshire.core         :as json]
            [clojure.zip           :as zip]
            [clojure.tools.logging :as log]))

(defprotocol TreeNode
  (branch? [node] "Is it possible for node to have children?")
  (node-children [node] "Returns children of this node.")
  (make-node [node children] "Makes new node from existing node and new children."))

(defprotocol Persistent
  (store! [category] "Dumps category into persistent storage.")
  (delete! [category] "Deletes category from tree and persistent storage"))

(defrecord Category [path props subcategories]
  TreeNode
  (branch? [node] true)
  (node-children [node] (:subcategories node))
  (make-node [node children] (Category. (:path node) (:props node) children)))

(def ^:dynamic *categories-tree* nil)

(defn tree-zip
  "Makes a zipper out of a tree."
  [root]
  (zip/zipper branch? node-children make-node root))

(defmacro with-tree
  "Changes default binding to zipper made of given tree."
  [tree & body]
  `(binding [*categories-tree* (tree-zip ~tree)]
     ~@body))

(defn- find-child-node
  "Looks for a node described by given path among direct children of loc."
  [loc path]
  (loop [node (zip/down loc)]
    (when node
      (if (= path (:path (zip/node node))) node (recur (zip/right node))))))

(defn- create-child-node
  "Inserts a node with given path as a rightmost child at loc.
  Moves location to newly inserted child."
  [loc path]
  (let [node (Category. path {} nil)]
    (zip/rightmost (zip/down (zip/append-child loc node)))))

(defn- find-or-create-node
  "Recursively looks for a node with a given path beginning at loc.
  When node was not found and create? is true whole the subtree (node and its children) are immediately created.
  Returns subtree with found (or eventually created) node as a root."
  [loc path create?]
  (if (or (empty? path) (= path (:path (first loc)))) ;; short-circut
    loc
    (loop [node loc
           curr ""
           [head & rest] (drop-while empty? (.split path "/"))]
      (let [cpath (str curr "/" head)
            child (or (find-child-node node cpath)
                      (when create? (create-child-node node cpath)))]
        (if (and rest child)
          (recur child cpath rest) child)))))

(defn- trail-at
  "Returns list of parents of node at given loc with node itsef included at first pos,
  its parent at second pos and so on."
  [loc]
  (map zip/node (take-while (complement nil?) (iterate zip/up loc))))

(defn- root-loc
  "Returns root loc of zipper.
  In contrast to zip/root it does not \"unzip\" the tree."
  [loc]
  (loop [node loc]
    (let [parent (zip/up node)]
      (if-not parent node (recur parent)))))

(defn- create-category-node
  "Creates new category node.
  Moves location to newly created node."
  [loc category]
  (when-let [node (find-or-create-node loc (:path category) true)]
    (zip/edit node assoc :props (:props category))))

(defn sticky?
  "Is property inherited down the category tree?"
  [prop]
  (:sticky prop))

(defn excluded?
  "Is property inherited down the category tree?"
  [prop]
  (:excluded prop))

(defn sticky-merge
  "Joins map m with [k v] only if v is sticky & not excluded.
  If v is excluded (and sticky) removes existing k key from m.
  Otherwise returns m."
  [m [k v]]
  (if (sticky? v)
    (if (excluded? v)
      (dissoc m k)
      (assoc m k (dissoc v :sticky)))
    m))

(defn stickify-props
  "Makes each property in m sticky by adding :sticky true"
  [m]
  (reduce-kv #(assoc %1 %2 (assoc %3 :sticky true)) {} m))

(defn collect-props
  "Calculates list of properties for given loc in category tree."
  [loc]
  (let [props (-> (mapv :props (trail-at loc))
                  (update-in [0] stickify-props))]
    (reduce #(reduce sticky-merge %1 %2) {} (rseq props))))

(defn lookup
  "Traverses a tree looking for a category of given path and
  recalculates props to reflect properties inheritance."
  [path]
  (when-let [loc (find-or-create-node *categories-tree* path false)]
    (-> (zip/node loc)
        (select-keys [:path :subcategories])
        (update :subcategories #(map :path %))
        (merge (collect-props loc)))))

(defn remove-at
  "Removes category at given path. Returns altered category tree."
  [path]
  (when-let [loc (find-or-create-node *categories-tree* path false)]
    (let [node (zip/node loc)]
      (when (satisfies? Persistent node)
        (delete! node)))
    (zip/root
     (zip/remove loc))))

(defn create-category
  "Adds new category. Returns altred category tree."
  [category]
  (let [loc (create-category-node *categories-tree* category)]
    (if (satisfies? Persistent category)
      (store! (zip/node loc)))
    (zip/root loc)))


(defn create-tree
  "Creates category tree basing on provided collection of category paths."
  [coll]
  (loop [[c & rest] coll
         loc (tree-zip (Category. "/" {} nil))]
    (if-not c
      (zip/root loc)
      (recur rest (root-loc (create-category-node loc c))))))

(defn from-file
  "Loads tree definition from external json-formatted file.
  Definition consists of an array of following map:

  {path: 'category', props: {'has_xenons': {type: 'bool', sticky: true}}}

  where:
  * category is path-like string category/subcategory/subsubcategory/...
  * props is map of category specific properties.

  Each property is described by type (eg. 'bool') and sticky/excluded flags
  used to perform inheritance calculations."
  ([path]
   (when-let [reader (clojure.java.io/reader path)]
     (log/info "Loading categories from" path)
     (when-let [tree (create-tree (json/parse-stream reader true))]
       (reset! *categories-tree* tree)))))

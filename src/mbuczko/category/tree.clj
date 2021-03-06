(ns mbuczko.category.tree
  (:require [cheshire.core :as json]
            [clojure.zip :as zip]))

(defprotocol TreeNode
  (branch? [node] "Is it possible for node to have children?")
  (node-children [node] "Returns children of this node.")
  (make-node [node children] "Makes new node from existing node and new children."))

(defprotocol Persistent
  (store! [category] "Dumps category into persistent storage.")
  (delete! [category] "Deletes category from tree and persistent storage"))

(defrecord Category [path props uuid subcategories]
  TreeNode
  (branch? [node] true)
  (node-children [node] (:subcategories node))
  (make-node [node children] (Category. (:path node) (:props node) (:uuid node) children)))

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
  (let [node (Category. path {} nil nil)]
    (zip/rightmost (zip/down (zip/append-child loc node)))))

(defn- find-or-create-node
  "Recursively looks for a node with a given path beginning at loc.
  When node was not found and create? is true whole the subtree (node and its children) are immediately created."
  [loc path create?]
  (if-not (= path (:path (first loc))) ;; short-circut
    (loop [node loc, [head & rest] (drop-while empty? (.split path "/"))]
      (let [child (or (find-child-node node head)
                      (when create? (create-child-node node head)))]
        (if (and rest child)
          (recur child rest) child))) loc))

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
  "Creates new category node at path with given props and uuid.
  If no uuid was provided a new one will be auto-generated.
  Moves location to newly created node."
  [loc path props uuid]
  (when-let [node (find-or-create-node loc path true)]
    (zip/edit node assoc
              :props props
              :uuid (or uuid (.toString (java.util.UUID/randomUUID))))))

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

(defn- update-children [opts children]
  "Modifies each child by:
    - excluding :props (no need to present them as they're internal details)
    - excluding :subcategories (no need to return all the children recursively)"
  (let [sorted (if (= (first opts) :sort-by)
                 (sort-by (second opts) children)
                 children)]
    (map #(dissoc % :props :subcategories) sorted)))

(defn lookup
  "Traverses a tree looking for a category of given path and
  recalculates props to reflect properties inheritance."
  [path & opts]
  (when-let [loc (find-or-create-node *categories-tree* path false)]
    (let [node (zip/node loc)]
      (-> node
          (select-keys [:uuid :subcategories])
          (update :subcategories (partial update-children opts))
          (assoc  :path path)
          (merge  (collect-props loc))))))

(defn remove-at
  "Removes category at given path. Returns altered category tree."
  [path]
  (when-let [loc (find-or-create-node *categories-tree* path false)]
    (let [node (zip/node loc)]
      (when (satisfies? Persistent node)
        (delete! (assoc node :path path))))
    (zip/root
     (zip/remove loc))))

(defn create-category
  "Adds new category at path with given props. Returns altred category tree."
  ([path props]
   (create-category path props nil))
  ([path props subcategories]
   (when-let [loc (create-category-node *categories-tree* path props nil)]
     (let [node (zip/node loc)]
       (if (satisfies? Persistent node)
         (store! (assoc node :path path)))
       (zip/root (if subcategories (zip/edit loc assoc :subcategories subcategories) loc))))))

(defn update-at
  "Updates category path and/or props. When path is changed - moves category with
  all its children to new location. Returns altered category tree."
  [path new-path props]
  (when (and new-path (not (= path new-path)))
    (when-let [loc (find-or-create-node *categories-tree* path false)]
      (let [node (zip/node loc)]
        (with-tree (remove-at path)
          (create-category new-path (or props (:props node)) (:subcategories node)))))))

(defn create-tree
  "Creates category tree basing on provided collection of category paths."
  [coll]
  (loop [[c & rest] coll
         loc (tree-zip (Category. "/" {} nil nil))]
    (if-not c
      (zip/root loc)
      (recur rest (root-loc (create-category-node loc (:path c) (:props c) (:uuid c)))))))

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
     (when-let [tree (create-tree (json/parse-stream reader true))]
       (reset! *categories-tree* tree)))))

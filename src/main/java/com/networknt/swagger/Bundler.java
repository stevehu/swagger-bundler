package com.networknt.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Bundler {

    public static ObjectMapper mapper = new ObjectMapper();

    static Map<String, Map<String, Object>> references = new ConcurrentHashMap<>();
    static String folder = null;
    static Map<String, Object> definitions = null;
    static boolean debug = false; 

    public static void main(String ... argv) {
    	
    		// check if debug output is requested
    		String isDebug = System.getProperty("debugOutput");
    		debug = (isDebug != null) && (isDebug.equals("")) ? true : false;
    	
        if(argv[0] != null) {
            folder = argv[0];
            System.out.println("Bundling API definition in folder: " + folder);
            // The input parameter is the folder that contains swagger.yaml and
            // this folder will be the base path to calculate remote references.
            Path path = Paths.get(argv[0], "swagger.yaml");
            try (InputStream is = Files.newInputStream(path)) {
                String json = null;
                Yaml yaml = new Yaml();
                Map<String, Object> map = (Map<String, Object>)yaml.load(is);

                // we have to work definitions as a separate map, otherwise, we will have
                // concurrent access exception while iterate map and update definitions.
                definitions = new HashMap<>();
                if(map.get("definitions") != null) {
                    definitions.putAll((Map<String, Object>)map.get("definitions"));
                }
                // now let's handle the references.
                resolveMap(map);
                // now the definitions might contains some references that are not in definitions.
                Map<String, Object> def = new HashMap<>(definitions);
                if(debug)
                		System.out.println("start resolve definitions first time ...");
                resolveMap(def);

                def = new HashMap<>(definitions);
                if(debug)
                		System.out.println("start resolve definitions second time ...");
                resolveMap(def);

                def = new HashMap<>(definitions);
                if(debug)
                		System.out.println("start resolve definitions second time ...");
                resolveMap(def);

                // now replace definitions in map.
                map.put("definitions", definitions);


                // convert the map back to json and output it.
                if(debug)
                		System.out.println("write bundled file to swagger.json ... same folder as the swagger.yaml input file...");
                json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);

                // write the output to swagger.json
                Files.write(Paths.get(argv[0], "swagger.json"), json.getBytes());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("ERROR: You must pass in a folder to a yaml file!");
        }
        
        System.out.println("Bundle complete...");
    }

    private static Map<String, Object> handlerPointer(String pointer) {
        Map<String, Object> result = new HashMap<>();
        if(pointer.startsWith("#")) {
            // There are two cases with local reference. 1, original in
            // local reference and it has path of "definitions" or 2, local reference
            // that extracted from reference file with reference to an object directly.
            String refKey = pointer.substring(pointer.lastIndexOf("/") + 1);
            //System.out.println("refKey = " + refKey);
            if(pointer.contains("definitions")) {
                // if the $ref is an object, keep it that way and if $ref is not an object, make it inline
                // and remove it from definitions.
                Map<String, Object> refMap = (Map<String, Object>)definitions.get(refKey);
                if(refMap == null) {
                    System.out.println("ERROR: Could not find reference in definitions for key " + refKey);
                    System.exit(0);
                }
                if(isRefMapObject(refMap)) {
                    result.put("$ref", pointer);
                } else {
                    result = refMap;
                }
            } else {
                // This is something extracted from extenal file and the reference is still local.
                // need to look up for all reference files in order to find it.
                Map<String, Object> refMap = null;
                for (Map<String, Object> r: references.values()) {
                    refMap = (Map<String, Object>)r.get(refKey);
                    if(refMap != null) break;
                }
                if(isRefMapObject(refMap)) {
                    definitions.put(refKey, refMap);
                    result.put("$ref", "#/definitions/" + refKey);
                } else {
                    result = refMap;
                }
            }
        } else {
            // external reference and it must be a relative url
            Map<String, Object> refs = loadRef(pointer.substring(0, pointer.indexOf("#")));
            String refKey = pointer.substring(pointer.indexOf("#/") + 2);
            //System.out.println("refKey = " + refKey);
            Map<String, Object> refMap = (Map<String, Object>)refs.get(refKey);
            // now need to resolve the internal references in refMap.
            if(refMap == null) {
                System.out.println("ERROR: Could not find reference in external file for pointer " + pointer);
                System.exit(0);
            }
            // check if the refMap type is object or not.
            if(isRefMapObject(refMap)) {
                // add to definitions
                definitions.put(refKey, refMap);
                // update the ref pointer to local
                result.put("$ref", "#/definitions/" + refKey);
            } else {
                // simple type, inline refMap instead.
                result = refMap;
            }
        }
        return result;
    }

    /**
     * Check if the input map is an json object or not.
     * @param refMap input map
     * @return
     */
    private static boolean isRefMapObject(Map<String, Object> refMap) {
        boolean result = false;
        for(Map.Entry<String, Object> entry: refMap.entrySet()) {
            if("type".equals(String.valueOf(entry.getKey())) && "object".equals(String.valueOf(entry.getValue()))) {
                result = true;
            }
        }
        return result;
    }

    /**
     * load and cache remote reference. folder is a static variable assigned by argv[0]
     * it will check the cache first and only load it if it doesn't exist in cache.
     *
     * @param path the path of remote file
     * @return map of remote references
     */
    private static Map<String, Object> loadRef(String path) {
        Map<String, Object> result = references.get(path);
        if(result == null) {
            Path p = Paths.get(folder, path);
            try (InputStream is = Files.newInputStream(p)) {
                Yaml yaml = new Yaml();
                result = (Map<String, Object>)yaml.load(is);
                references.put(path, result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    /**
     * It deep iterate a map object and looking for "$ref" and handle it.
     * @param map the map of swagger.yaml
     */
    public static void resolveMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if(debug)
            		System.out.println("resolveMap key = " + key + " value = " + value);
            if (value instanceof Map) {
                // check if this map is $ref, it should be size = 1
                if (((Map) value).size() == 1) {
                    Set keys = ((Map)value).keySet();
                    for (Iterator i = keys.iterator(); i.hasNext();) {
                        String k = (String)i.next();
                        if("$ref".equals(k)) {
                            String pointer = (String)((Map)value).get(k);
                            if(debug)
                            		System.out.println("pointer = " + pointer);
                            Map refMap = handlerPointer(pointer);
                            entry.setValue(refMap);
                        }
                    }
                }
                resolveMap((Map) value);
            } else if (value instanceof List) {
                resolveList((List)value);
            } else {
                continue;
            }
        }
    }

    public static void resolveList(List list) {
        for (int i = 0; i < list.size(); i++) {
            if(list.get(i) instanceof Map) {
                // check if this map is $ref
                if (((Map) list.get(i)).size() == 1) {
                    Set keys = ((Map)list.get(i)).keySet();
                    for (Iterator j = keys.iterator(); j.hasNext();) {
                        String k = (String)j.next();
                        if("$ref".equals(k)) {
                            String pointer = (String)((Map)list.get(i)).get(k);
                            list.set(i, handlerPointer(pointer));
                        }
                    }
                }
                resolveMap((Map<String, Object>)list.get(i));
            } else if(list.get(i) instanceof List) {
                resolveList((List)list.get(i));
            } else {
                continue;
            }
        }
    }

}

class UrlMappings {

	static mappings = {
        /*
        "/$namespace/$controller/$action?/$id?(.$format)?" {
            constraints {
                // apply constraints here
            }
        }
        */
        "/v2/catalog" (controller: 'broker', action: 'catalog', method: 'GET')
        "/v2/service_instances/$instance_id" (controller: 'broker') { action = [ PUT: 'create', DELETE: 'delete' ] }
        "/v2/service_instances/$instance_id/service_bindings/$binding_id" (controller: 'broker') { action = [ PUT: 'bind', DELETE: 'unbind' ] }

        "/"(view:"/index")
        "500"(view:'/error')
	}
}

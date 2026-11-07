component extends="org.lucee.cfml.test.LuceeTestCase" labels="osgi" {

	function beforeAll() {
		variables.service = getPageContext().getConfig().getFactory().getEngine().getOSGiService();
	}

	function run( testResults, testBox ) {
		describe( "OSGi extension", function() {

			it( "has no id and no info for a class that is not from a bundle", function() {
				var clazz = createObject( "java", "java.lang.String" ).getClass();
				expect( isNull( service.getId( clazz ) ) ).toBeTrue();
				expect( isNull( service.getInfo( clazz ) ) ).toBeTrue();
			});

			it( "has no id for the classes of Lucee itself", function() {
				expect( isNull( service.getId( createObject( "java", "lucee.runtime.PageContextImpl" ).getClass() ) ) ).toBeTrue();
			});

			it( "throws for a bundle that does not exist", function() {
				expect( function() {
					service.getClassLoader( "does.not.exist", "1.0.0" );
				} ).toThrow();
			});

		});
	}

}

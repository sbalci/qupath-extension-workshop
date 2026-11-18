/** QuPath script entry point: candidate JAR, acceptance script, new output directory, optional 'gui'. */
import groovy.transform.CompileStatic

@CompileStatic
class LocalWorkflowCandidateLoader extends URLClassLoader {
    LocalWorkflowCandidateLoader(URL url,ClassLoader parent){super([url] as URL[],parent)}
    @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
        if(name.startsWith('io.github.sbalci.qupath.workshop.')){
            synchronized(getClassLoadingLock(name)){
                Class<?> found=findLoadedClass(name)
                if(found==null)found=findClass(name)
                if(resolve)resolveClass(found)
                return found
            }
        }
        return super.loadClass(name,resolve)
    }
    @Override URL getResource(String name){
        if(name.startsWith('scripts/')||name.startsWith('python/')||name.startsWith('i18n/')||name=='build-info.properties'||name=='local-workflow-v1.schema.json'){
            URL resource=findResource(name)
            if(resource!=null)return resource
        }
        return super.getResource(name)
    }
}

assert args.length>=3:'Pass candidate JAR, acceptance script and a new output directory'
def candidate=new File(args[0]).canonicalFile
assert candidate.isFile()
def loader=new LocalWorkflowCandidateLoader(candidate.toURI().toURL(),this.class.classLoader)
def values=[args[2]]
if(args.length>3)values.add(args[3])
def shell=new GroovyShell(loader,new Binding([args:values as String[]]))
shell.evaluate(new File(args[1]))
